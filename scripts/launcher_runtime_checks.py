#!/usr/bin/env python3
"""Inspect the real opt-in Minecraft fixture (no mocks, no OS-specific audio routing)."""
import json
import os
from pathlib import Path
import time

ROOT = Path(os.environ.get('CMS_LAUNCHER_RUN_DIR', str(Path.cwd() / 'run-launcher-debug')))
OUT = Path(os.environ.get('CMS_LAUNCHER_RESULTS', str(Path.cwd() / 'build/cell-validation/runtime.jsonl')))

def read_probe(directory):
    with (directory / 'probe.jsonl').open('rb') as stream:
        stream.seek(0, 2)
        stream.seek(max(0, stream.tell() - 131072))
        lines = stream.read().splitlines()
    for line in reversed(lines):
        try:
            row = json.loads(line)
            if time.time() * 1000 - row['wall'] > 5000:
                raise AssertionError('The debug client probe is stale')
            return row
        except (ValueError, UnicodeDecodeError):
            pass
    raise ValueError('No complete probe row')

def latest():
    return read_probe(ROOT)

def wait_for(predicate, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        row = latest()
        if predicate(row):
            return row
        time.sleep(.15)
    raise AssertionError(latest())

def command(action):
    target = ROOT / 'command'
    assert not target.exists(), 'Another debug command is pending'
    before = latest()['wall']
    target.write_text(action + '\n', encoding='ascii')
    return wait_for(lambda row: not target.exists() and row['wall'] > before + 200)

def result(name, row):
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open('a', encoding='utf-8') as out:
        out.write(json.dumps({'check': name, 'result': 'PASS', 'probe': row}) + '\n')
    print('PASS', name, flush=True)

def music(row):
    return [sound for sound in row['sounds'] if sound['class'] in ('StreamSound', 'FlyingJukeboxSound')]

if __name__ == '__main__':
    command('cell-demo vanilla')
    try:
        command('reload')
        result('one_cell_reserved', wait_for(lambda row: row['reserved'] == 1 and row['loaded'] == 0))
        row = wait_for(lambda row: row['loaded'] == 24 and row['reserved'] == 0)
        master = row['master']
        result('copper_cell_24_shots', row)
        command('fire')
        row = wait_for(lambda row: row['loaded'] == 23 and any(disc['embedded'] for disc in row['discs']))
        assert row['master'] == master and row['cellEnergy'] == 23000
        result('projection_spends_energy_keeps_original', row)
        result('real_mono_audio_plays', wait_for(lambda row: any(sound.get('alState') == 4114 and sound.get('channels') == 1 for sound in music(row))))
        ids = {disc['uuid'] for disc in row['discs']}
        command('savecheck')
        command('pickup')  # Move a survival-capable listener onto the projection: it must stay there.
        time.sleep(1)
        result('projections_cannot_be_recovered', wait_for(lambda row: ids <= {disc['uuid'] for disc in row['discs']} and row['master'] == master))
        command('cell-eject')
        result('empty_breech_after_eject', wait_for(lambda row: row['cellLevel'] == 0 and row['reserved'] == 0))
    finally:
        command('cell-demo-end')
