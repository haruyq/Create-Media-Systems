import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "build" / "lighting-network"
DEMO_MEDIA = ROOT / "src" / "lightingDemo" / "resources" / "cms-lighting-lab.mp4"

SERVER_PROPERTIES = """server-ip=127.0.0.1
server-port=25583
online-mode=false
enforce-secure-profile=false
max-players=4
gamemode=creative
level-name=CMS Lighting Lab
difficulty=peaceful
spawn-protection=0
view-distance=6
simulation-distance=6
"""

CLIENT_OPTIONS = """version:3955
fullscreen:false
renderDistance:6
simulationDistance:6
maxFps:30
pauseOnLostFocus:false
"""

CLIENT_CONFIG = """youtubeCookies = false
sendDiagnostics = false
"""


def write_missing(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as file:
            file.write(content)
    except FileExistsError:
        pass


def copy_missing(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        output = target.open("xb")
    except FileExistsError:
        return
    try:
        with output, source.open("rb") as media:
            shutil.copyfileobj(media, output)
    except BaseException:
        target.unlink()
        raise


def prepare(run, accept_eula, media, ffmpeg):
    server = run / "server"
    eula = server / "eula.txt"
    if not eula.exists() and not accept_eula:
        raise ValueError("Review the Minecraft EULA and pass --accept-eula to create the server's eula.txt")

    target = server / "media" / DEMO_MEDIA.name
    if target.exists():
        print(f"Kept existing media: {target}")
    elif media is not None:
        copy_missing(media, target)
        print(f"Copied supplied media: {target}")
    elif ffmpeg is not None:
        with tempfile.TemporaryDirectory() as directory:
            looped = Path(directory) / DEMO_MEDIA.name
            subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-stream_loop", "2", "-i", str(DEMO_MEDIA),
                            "-t", "180", "-c", "copy", "-movflags", "+faststart", str(looped)], check=True)
            copy_missing(looped, target)
        print(f"Prepared 180-second media: {target}")
    else:
        copy_missing(DEMO_MEDIA, target)
        print(f"Prepared 60-second media: {target}. Use -PlightingNetworkSeconds=60 for all three Gradle runs.")

    if accept_eula:
        write_missing(eula, "eula=true\n")
    write_missing(server / "server.properties", SERVER_PROPERTIES)
    for client in ("a", "b"):
        write_missing(run / client / "options.txt", CLIENT_OPTIONS)
        write_missing(run / client / "config" / "cms-client.toml", CLIENT_CONFIG)
        write_missing(run / f"delay-{client}.json", '{"delay_ms":0,"jitter_ms":0}\n')
    print(f"Lighting network fixture ready: {run}")


def main():
    parser = argparse.ArgumentParser(description="Create missing files for the local two-client lighting test.")
    parser.add_argument("--accept-eula", action="store_true", help="Accept the Minecraft EULA for this test server")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--media", type=Path, help="Use a ready-to-play MP4 instead of the bundled test clip")
    source.add_argument("--ffmpeg", help="FFmpeg executable for extending the bundled clip to 180 seconds")
    args = parser.parse_args()
    if args.media is not None and not args.media.is_file():
        parser.error(f"Media file not found: {args.media}")
    try:
        prepare(RUN, args.accept_eula, args.media, args.ffmpeg or shutil.which("ffmpeg"))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
