#!/usr/bin/env python3
"""Standard-library analysis for paired lighting-network JSONL captures."""
import argparse
import json
import statistics
from pathlib import Path


def rows(path):
    if not path.exists():
        return []
    result = []
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                result.append(value)
    return result


def wall(row):
    if row.get("wallMillis") is not None:
        return float(row["wallMillis"])
    return float(row.get("wallNanos", 0)) / 1_000_000.0


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, round((len(values) - 1) * p))]


def key(row):
    return row.get("source"), row.get("session"), row.get("track")


def select_session(server, events):
    if not server:
        return None
    event_times = [event["time"] * 1000 for event in events if "time" in event]
    lo, hi = (min(event_times), max(event_times)) if event_times else (0, float("inf"))
    groups = {}
    for row in server:
        groups.setdefault(row.get("session"), []).append(row)
    ranked = []
    for session, values in groups.items():
        times = [wall(row) for row in values]
        overlap = max(0, min(max(times), hi) - max(min(times), lo))
        ranked.append((overlap, sum(lo <= time <= hi for time in times), session))
    return max(ranked)[2]


def phase_ranges(events):
    if not events:
        return []
    times = [event["time"] * 1000 for event in events if "time" in event]
    baseline_end = next((event["time"] * 1000 for event in events if event.get("event") == "baseline_end"), None)
    baseline_start = next((event["time"] * 1000 for event in events if event.get("event") == "baseline"), None)
    if baseline_end is None:
        baseline_end = next((event["time"] * 1000 for event in events if event.get("event") == "delay"), min(times))
    ranges = [{"name": "baseline", "from": baseline_start or baseline_end - 20_000, "to": baseline_end}]
    delay_events = [event for event in events if event.get("event") == "delay"]
    for index, event in enumerate(delay_events):
        start = event["time"] * 1000 + 5_000
        following = [candidate["time"] * 1000 for candidate in events if candidate["time"] > event["time"] and candidate.get("event") in {"delay", "owner_exit", "end"}]
        ranges.append({"name": f"delay_{event.get('a_ms', event.get('one_way_ms'))}_{event.get('b_ms', event.get('one_way_ms'))}_jitter_{event.get('jitter_ms', 0)}", "from": start,
                       "to": min(following) if following else start})
    return ranges


def pair_stats(left, right):
    candidates = []
    for row in left:
        if row.get("sample") is None:
            continue
        matches = [other for other in right if other.get("sample") is not None and key(other) == key(row)
                   and abs(wall(other) - wall(row)) <= 150]
        if matches:
            other = min(matches, key=lambda value: abs(wall(value) - wall(row)))
            candidates.append((wall(row), row, other))
    clocks_corrected = [abs((row["positionMillis"] - other["positionMillis"]) - (wall(row) - wall(other)))
                        for _, row, other in candidates]
    phases = [abs(row["sample"].get("motionPhase", 0) - other["sample"].get("motionPhase", 0))
              for _, row, other in candidates]
    equal_pts = []
    for _, row, other in candidates:
        raw_a, raw_b = row.get("rawLatest"), other.get("rawLatest")
        if raw_a and raw_b and raw_a.get("millis") == raw_b.get("millis"):
            equal_pts.append({"millis": raw_a["millis"], "levelDiff": abs(raw_a.get("level", 0) - raw_b.get("level", 0)),
                              "leftEqual": raw_a.get("left") == raw_b.get("left"), "rightEqual": raw_a.get("right") == raw_b.get("right")})
    return {"pairs": len(candidates),
            "clockMedian": statistics.median(clocks_corrected) if clocks_corrected else None,
            "clockP95": percentile(clocks_corrected, .95), "phaseMedian": statistics.median(phases) if phases else None,
            "phaseP95": percentile(phases, .95), "equalPTSCount": len(equal_pts),
            "equalPTSMismatches": sum(row['levelDiff'] != 0 or not row['leftEqual'] or not row['rightEqual'] for row in equal_pts)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", nargs="?", default="build/lighting-network/before")
    parser.add_argument("--output")
    args = parser.parse_args()
    root = Path(args.directory)
    events = rows(root / "events.jsonl")
    server = rows(root / "server.jsonl")
    all_clients = {name: rows(root / f'{name}.jsonl') for name in ('a', 'b')}
    offsets = [row['wallMillis'] - row['wallNanos'] / 1_000_000 for data in all_clients.values() for row in data
               if 'wallMillis' in row and 'wallNanos' in row]
    if offsets:
        offset = statistics.median(offsets)
        for row in server:
            if 'wallMillis' not in row and 'wallNanos' in row:
                row['wallMillis'] = row['wallNanos'] / 1_000_000 + offset
    session = select_session(server, events)
    clients = {name: [row for row in data if session is None or row.get('session') == session]
               for name, data in all_clients.items()}
    phases = phase_ranges(events)
    report = {"capture": str(root), "session": session, "phases": phases, "clients": {}}
    report['motion'] = {}
    for name, data in all_clients.items():
        groups = {}
        for row in data:
            if 'rawLatest' in row:
                raw = row['rawLatest']
                groups.setdefault((row['session'], row['generation']), {})[raw['millis']] = raw['motionPhase']
        comparisons = reversals = 0
        for group in groups.values():
            values = sorted(group.items())
            for before, after in zip(values, values[1:]):
                comparisons += 1
                reversals += after[1] < before[1] - 1e-8
        report['motion'][name] = dict(compared=comparisons, reversals=reversals)
    for name, data in clients.items():
        phase_report = []
        for phase in phases:
            scoped = [row for row in data if phase["from"] <= wall(row) < phase["to"]]
            phase_report.append({"name": phase["name"], "rows": len(scoped),
                                 "samples": sum(row.get("sample") is not None for row in scoped),
                                 "availability": sum(row.get("sample") is not None for row in scoped) / max(1, len(scoped))})
        generation2 = [row for row in data if row.get("generation", 0) >= 2 and row.get("sample") is not None]
        report["clients"][name] = {"rows": len(data), "samples": sum(row.get("sample") is not None for row in data),
                                    "firstGeneration2Sample": wall(generation2[0]) if generation2 else None, "phases": phase_report}
    names = list(clients)
    if len(names) >= 2:
        report["paired"] = pair_stats(clients[names[0]], clients[names[1]])
        report['pairedPhases'] = {phase['name']: pair_stats(
            [row for row in clients[names[0]] if phase['from'] <= wall(row) < phase['to']],
            [row for row in clients[names[1]] if phase['from'] <= wall(row) < phase['to']]) for phase in phases}
        report['publishedFrames'] = {}
        for field in ('rawLatest', 'rawPrepared'):
            maps = [{(key(row), row[field]['millis']): row[field] for row in data if field in row}
                    for data in all_clients.values()]
            shared = maps[0].keys() & maps[1].keys()
            report['publishedFrames'][field] = {'compared': len(shared),
                'mismatches': sum(maps[0][item] != maps[1][item] for item in shared)}
        report['tracks'] = {}
        for track in sorted({row.get('track') for data in all_clients.values() for row in data}):
            data = {name: [row for row in values if row.get('track') == track] for name, values in all_clients.items()}
            report['tracks'][track] = {'paired': pair_stats(data[names[0]], data[names[1]]),
                'preparedSamples': {name: sum(bool(row.get('prepared')) and row.get('sample') is not None for row in values)
                                    for name, values in data.items()}}
    scoped_server = [row for row in server if session is None or row.get("session") == session]
    handoffs = []
    for event in events:
        if event.get("event") != "owner_exit":
            continue
        exit_ms = event["time"] * 1000
        previous = [row for row in server if wall(row) < exit_ms]
        old_owner = previous[-1].get('owner') if previous else None
        active_session = previous[-1].get('session') if previous else None
        next_rows = [row for row in server if row.get('session') == active_session and wall(row) >= exit_ms
                     and row.get("owner") not in {old_owner, None, "00000000-0000-0000-0000-000000000000"}]
        if next_rows:
            first = next_rows[0]
            restored = sorted([row for data in all_clients.values() for row in data if wall(row) >= exit_ms
                               and row.get('session') == active_session and row.get('generation') == first.get('generation')
                               and row.get('sample') is not None], key=wall)
            published = [row for row in next_rows if row.get('generation') == first.get('generation') and row.get('liveSequence', -1) > 0]
            handoffs.append({"session": active_session, "exitMillis": exit_ms, "newOwnerMillis": wall(first), "gapMillis": wall(first) - exit_ms,
                             "owner": first.get("owner"), "generation": first.get("generation"),
                             "firstPublishGapMillis": wall(published[0]) - exit_ms if published else None,
                             "firstSampleGapMillis": wall(restored[0]) - exit_ms if restored else None})
    report["serverRows"] = len(scoped_server)
    report["handoffs"] = handoffs
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
