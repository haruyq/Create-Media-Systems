"""Speaker stress test: ~100 speakers (mix, left, right) on each of a jukebox, a disc reader and a Blu-ray player,
through repeated server restarts and client reconnects. Runs the dedicated server and the client with
-PspeakerStress (see src/speakerStress), and checks after each step that every side of every source plays here:
all its emitters, its stream on a sound channel, OpenAL playing it, its samples moving, and the right side of the
stereo file (left 440 Hz, right 660 Hz, mix both).

    python scripts/speaker_stress.py --accept-eula [--server-restarts 4] [--reconnects 4]
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "build" / "speaker-stress"
SERVER = RUN / "server"
CLIENT = RUN / "client"
SOURCES = ("jukebox", "reader", "bluray")
CHANNELS = ("MIX", "LEFT", "RIGHT")
EXPECTED = {f"{s}/{c}": 33 + (1 if s == "jukebox" and c == "MIX" else 0) for s in SOURCES for c in CHANNELS}
MEDIA_SECONDS = 3600

SERVER_PROPERTIES = """server-ip=127.0.0.1
server-port=25590
online-mode=false
enforce-secure-profile=false
level-type=minecraft\\:flat
generate-structures=false
spawn-monsters=false
spawn-animals=false
gamemode=creative
difficulty=peaceful
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=4
"""
CLIENT_OPTIONS = """version:3955
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
narrator:0
renderDistance:6
simulationDistance:6
maxFps:30
soundCategory_master:0.15
"""


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def prepare(ffmpeg):
    for path, text in [(SERVER / "eula.txt", "eula=true\n"), (SERVER / "server.properties", SERVER_PROPERTIES),
                       (SERVER / "config" / "cms-server.toml",
                        'apiUrl = "http://127.0.0.1:25591"\nallowedHosts = ["127.0.0.1"]\n'),
                       (CLIENT / "options.txt", CLIENT_OPTIONS),
                       (CLIENT / "config" / "cms-client.toml", "youtubeCookies = false\nsendDiagnostics = false\n")]:
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            path.write_text(text, encoding="utf-8")
    media = SERVER / "media" / "stress.mp4"
    if not media.exists():
        media.parent.mkdir(parents=True, exist_ok=True)
        log("Making the stereo test media (left 440 Hz, right 660 Hz)")
        subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-y",
                        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={MEDIA_SECONDS}",
                        "-f", "lavfi", "-i", f"sine=frequency=660:sample_rate=48000:duration={MEDIA_SECONDS}",
                        "-f", "lavfi", "-i", f"color=c=0x203040:s=320x180:r=10:d={MEDIA_SECONDS}",
                        "-filter_complex", "[0:a][1:a]join=inputs=2:channel_layout=stereo[a]",
                        "-map", "2:v", "-map", "[a]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "40",
                        "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", str(media)], check=True)


class Game:
    """One gradle run (the server or the client), its output in its own log."""

    def __init__(self, task, name):
        self.task = task
        self.name = name
        self.process = None
        self.log = None
        self.runs = 0

    def start(self):
        self.runs += 1
        self.log = RUN / f"{self.name}-{self.runs}.log"
        output = self.log.open("w", encoding="utf-8", errors="replace")
        gradlew = str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
        self.process = subprocess.Popen([gradlew, self.task, "-PspeakerStress", "--offline", "--console=plain"],
                                        cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        log(f"Started {self.name} (run {self.runs}, log {self.log.name})")

    def text(self):
        return self.log.read_text(encoding="utf-8", errors="replace") if self.log and self.log.exists() else ""

    def wait_for(self, marker, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if marker in self.text():
                return True
            if self.process.poll() is not None:
                raise RuntimeError(f"{self.name} exited ({self.process.returncode}) before '{marker}'; see {self.log}")
            time.sleep(1)
        raise RuntimeError(f"{self.name} did not reach '{marker}' within {timeout}s; see {self.log}")

    def wait_exit(self, timeout):
        try:
            self.process.wait(timeout)
            return True
        except subprocess.TimeoutExpired:
            return False


def lines(path, since):
    if not path.exists():
        return []
    result = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if row.get("wallMillis", 0) >= since * 1000:
            result.append(row)
    return result


def problems(row, previous):
    """What is wrong with one client sample (empty if every side plays as it should)."""
    found = []
    if not row.get("connected"):
        return [f"not connected ({row.get('screen')})"]
    sides = row.get("sides", {})
    for name, expected in EXPECTED.items():
        side = sides.get(name)
        if side is None:
            found.append(f"{name}: no stream")
            continue
        channel = name.split("/")[1]
        bad = []
        if side["emitters"] != expected:
            bad.append(f"emitters {side['emitters']}/{expected}")
        for flag in ("present", "nearby", "playing", "active"):
            if not side[flag]:
                bad.append(f"not {flag}")
        if side["waiting"]:
            bad.append("waiting for a channel")
        if side["al"] != "playing":
            bad.append(f"OpenAL {side['al']}")
        if side["gain"] <= 0:
            bad.append("gain 0")
        old = (previous or {}).get("sides", {}).get(name)
        if old is not None and side["newestPts"] == old["newestPts"]:
            bad.append("samples not moving")
        if side["samples"] < 12000:
            bad.append(f"only {side['samples']} samples played")
        p440, p660 = side["p440"], side["p660"]
        floor = 1e-4
        if channel == "LEFT" and not (p440 > floor and p440 > 20 * p660):
            bad.append(f"not the left side (440={p440:.2g}, 660={p660:.2g})")
        if channel == "RIGHT" and not (p660 > floor and p660 > 20 * p440):
            bad.append(f"not the right side (440={p440:.2g}, 660={p660:.2g})")
        if channel == "MIX" and not (p440 > floor and p660 > floor and 0.2 < p440 / max(p660, 1e-12) < 5):
            bad.append(f"not both sides (440={p440:.2g}, 660={p660:.2g})")
        if bad:
            found.append(f"{name}: " + ", ".join(bad))
    for name in sides:
        if name not in EXPECTED:
            found.append(f"{name}: unexpected stream")
    if row.get("orphans"):
        found.append(f"{row['orphans']} streams without a group")
    if row.get("engineStreams") != len(EXPECTED):
        found.append(f"{row.get('engineStreams')} CMS streams in the sound engine, not {len(EXPECTED)}")
    return found


def server_problems(row):
    found = []
    for name, expected in EXPECTED.items():
        relays, listeners = row.get("sides", {}).get(name, [0, 0])
        if relays != expected or listeners != expected:
            found.append(f"{name}: server relays {relays}, listened {listeners}, expected {expected}")
    return found


def kept_position(label, before, results):
    """Whether every source carried on from where it was when the server stopped (not back where it last saved)."""
    rows = lines(SERVER / "stress-server.jsonl", 0)
    old = [r for r in rows if r["wallMillis"] < before * 1000]
    new = [r for r in rows if r["wallMillis"] >= before * 1000]
    if not old or not new:
        results.append({"step": f"{label}: position kept", "ok": False, "problems": ["no server samples"]})
        return
    back = {name: (old[-1]["sources"][name] - new[0]["sources"][name]) / 20 for name in SOURCES}
    bad = [f"{name} went back {seconds:.0f} s" for name, seconds in back.items() if seconds > 3]
    results.append({"step": f"{label}: position kept", "ok": not bad, "problems": bad})
    log(f"{label}: {'every source carried on' if not bad else 'POSITION LOST: ' + ', '.join(bad)}")


def observe(label, since, settle, hold, timeout, results):
    """Waits until every client sample has been healthy for `hold` seconds (after `settle`), up to `timeout`."""
    client_log = CLIENT / "stress-client.jsonl"
    server_log = SERVER / "stress-server.jsonl"
    deadline = since + timeout
    healthy_since = None
    last_problems = []
    while time.time() < deadline:
        time.sleep(2)
        rows = lines(client_log, since + settle)
        healthy_since = None
        previous = None
        for row in rows:
            issue = problems(row, previous)
            previous = row
            if issue:
                healthy_since = None
                last_problems = issue
            elif healthy_since is None:
                healthy_since = row["wallMillis"] / 1000
        if healthy_since is not None and rows and rows[-1]["wallMillis"] / 1000 - healthy_since >= hold:
            server_rows = [r for r in lines(server_log, healthy_since) if r.get("players")]
            server_issue = server_problems(server_rows[-1]) if server_rows else ["no server samples"]
            first_ok = healthy_since - since
            results.append({"step": label, "ok": not server_issue, "healthyAfterSeconds": round(first_ok, 1),
                            "problems": server_issue})
            log(f"{label}: {'OK' if not server_issue else 'SERVER MISMATCH'}, every side healthy "
                f"{first_ok:.0f}s after the step" + ("" if not server_issue else f": {server_issue}"))
            return
    results.append({"step": label, "ok": False, "problems": last_problems})
    log(f"{label}: FAILED, still wrong after {timeout}s: {last_problems}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--accept-eula", action="store_true", help="Accept the Minecraft EULA for the test server")
    parser.add_argument("--server-restarts", type=int, default=4)
    parser.add_argument("--reconnects", type=int, default=4)
    parser.add_argument("--away", type=int, default=1, help="times the client leaves for 20 s")
    parser.add_argument("--away-restarts", type=int, default=1,
                        help="times the server restarts while the client is away, then it joins")
    parser.add_argument("--hold", type=int, default=20, help="seconds every side must stay healthy")
    parser.add_argument("--timeout", type=int, default=150, help="seconds a step may take to become healthy")
    args = parser.parse_args()
    if not (SERVER / "eula.txt").exists() and not args.accept_eula:
        parser.error("Review the Minecraft EULA (https://aka.ms/MinecraftEULA) and pass --accept-eula")
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        parser.error("ffmpeg is needed to make the test media")
    prepare(ffmpeg)
    for stale in ("stop-server",):
        (SERVER / stale).unlink(missing_ok=True)
    for stale in ("client-quit", "client-disconnect", "client-hold"):
        (CLIENT / stale).unlink(missing_ok=True)

    server = Game("runStressServer", "server")
    client = Game("runStressClient", "client")
    results = []
    try:
        server.start()
        server.wait_for("Speaker stress server ready", 600)
        client.start()
        observe("first join", time.time(), 30, args.hold, 600, results)

        for i in range(1, args.server_restarts + 1):
            log(f"Server restart {i}")
            (SERVER / "stop-server").touch()
            if not server.wait_exit(180):
                raise RuntimeError("the server did not stop")
            stopped = time.time()
            server.start()
            server.wait_for("Speaker stress server ready", 600)
            time.sleep(3)
            kept_position(f"server restart {i}", stopped, results)
            observe(f"server restart {i}", time.time(), 10, args.hold, args.timeout, results)

        for i in range(1, args.reconnects + 1):
            log(f"Client reconnect {i}")
            started = time.time()
            (CLIENT / "client-disconnect").touch()
            observe(f"client reconnect {i}", started, 8, args.hold, args.timeout, results)

        for i in range(1, args.away + 1):
            log(f"Client away for 20 s, then back ({i})")
            started = time.time()
            (CLIENT / "client-hold").touch()
            (CLIENT / "client-disconnect").touch()
            time.sleep(20)
            (CLIENT / "client-hold").unlink()
            observe(f"client away 20 s {i}", started, 25, args.hold, args.timeout, results)

        for i in range(1, args.away_restarts + 1):
            log(f"Server restart while the client stays away ({i})")
            (CLIENT / "client-hold").touch()
            (CLIENT / "client-disconnect").touch()
            time.sleep(5)
            (SERVER / "stop-server").touch()
            server.wait_exit(180)
            stopped = time.time()
            server.start()
            server.wait_for("Speaker stress server ready", 600)
            time.sleep(3)
            kept_position(f"server restart while away {i}", stopped, results)
            (CLIENT / "client-hold").unlink()
            observe(f"server restart, then join {i}", time.time(), 8, args.hold, args.timeout, results)
    finally:
        (CLIENT / "client-quit").touch()
        (SERVER / "stop-server").touch()
        for game in (client, server):
            if game.process and not game.wait_exit(120):
                game.process.kill()
        # A stream the engine dropped and CMS started again is a gap in the sound: there should be none here.
        dropped = [line for run in range(1, client.runs + 1)
                   for line in (RUN / f"client-{run}.log").read_text(encoding="utf-8", errors="replace").splitlines()
                   if "dropped CMS stream" in line]
        results.append({"step": "no stream dropped and restarted", "ok": not dropped, "problems": dropped})
        log(f"Streams dropped and restarted: {len(dropped)}")
        report = RUN / "report.json"
        report.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        failed = [r for r in results if not r["ok"]]
        log(f"{len(results) - len(failed)}/{len(results)} steps OK; report: {report}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
