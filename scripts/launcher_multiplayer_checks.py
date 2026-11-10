#!/usr/bin/env python3
"""Verify two independent CD clocks before/after a listener's actual chunk unload."""
import json
import os
import time
from pathlib import Path
from launcher_runtime_checks import ROOT, command, latest, read_probe, result

OBSERVER = Path(os.environ.get('CMS_LAUNCHER_OBSERVER_DIR', str(ROOT.parent / 'run-launcher-observer')))
URL = os.environ.get('CMS_LAUNCHER_TEST_URL', 'https://youtu.be/m1TPvPU9WTw?si=eGxskvi9oXZlcCF6')

def observed():
    return read_probe(OBSERVER)

def both(predicate, timeout=45):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        a, b = latest(), observed()
        if predicate(a, b):
            return a, b
        time.sleep(.2)
    raise AssertionError([latest(), observed()])

def synchronized(row, ids):
    found = {disc['uuid']: disc for disc in row['discs'] if disc['uuid'] in ids}
    if len(found) != 2:
        return False
    ordered = sorted(found.values(), key=lambda disc: disc['elapsed'], reverse=True)
    audio_seconds = []
    for disc in ordered:
        audio = disc.get('audio') or {}
        seconds = audio.get('seconds', -1000)
        playing = any(sound.get('alState') == 4114 and abs(sound['x'] - disc['x']) < .01
                      and abs(sound['y'] - disc['y']) < .01 for sound in row['sounds'])
        if audio.get('loading', True) or not playing or abs(seconds - disc['elapsed']) >= 1.5:
            return False
        audio_seconds.append(seconds)
    return abs(audio_seconds[0] - audio_seconds[1] - 11) < 1.5

if __name__ == '__main__':
    both(lambda a, b: a['screen'] == b['screen'] == 'none', 90)
    existing = {disc['uuid'] for disc in latest()['discs']}
    command('playback-check ' + URL)
    a, b = both(lambda a, b: len({disc['uuid'] for disc in a['discs']} - existing) == 2)
    ids = {disc['uuid'] for disc in a['discs']} - existing
    try:
        a, b = both(lambda a, b: synchronized(a, ids) and synchronized(b, ids))
        result('two_server_cd_clocks_play_on_both_clients', {'host': a, 'observer': b})
        command('observer-away')
        a, b = both(lambda a, b: not (ids & {disc['uuid'] for disc in b['discs']}) and not b['sounds'])
        result('out_of_range_unloads_entities_and_releases_audio', {'host': a, 'observer': b})
        time.sleep(3)
        command('observer-return')
        a, b = both(lambda a, b: synchronized(a, ids) and synchronized(b, ids))
        result('chunk_reload_seeks_each_cd_to_its_server_position', {'host': a, 'observer': b})
        command('all-away')
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            server = json.loads((ROOT / 'server-probe.json').read_text())
            if time.time() * 1000 - server['wall'] < 2500 and not (ids & server['discs'].keys()):
                break
            time.sleep(.25)
        else:
            raise AssertionError('The server never unloaded the original disc chunk')
        result('server_also_unloads_the_disc_entities', server)
        command('all-return')
        a, b = both(lambda a, b: synchronized(a, ids) and synchronized(b, ids))
        result('server_chunk_deserialization_keeps_uuid_and_individual_clocks', {'host': a, 'observer': b})
    finally:
        command('all-return')
        command('observer-return')
        command('playback-check-clear')
    a, b = both(lambda a, b: not (ids & {disc['uuid'] for disc in a['discs']})
                and not (ids & {disc['uuid'] for disc in b['discs']}) and not a['sounds'] and not b['sounds'])
    result('removal_leaves_no_ghost_audio', {'host': a, 'observer': b})
