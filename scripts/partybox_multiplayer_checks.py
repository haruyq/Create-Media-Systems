"""Actual two-client/real-server-chunk continuity checks for the opt-in debug fixture."""
import json
import os
import time
from pathlib import Path
from launcher_runtime_checks import ROOT, command, latest, read_probe

OBSERVER = ROOT.parent / 'run-launcher-observer'
OUTPUT = Path(os.environ.get('CMS_PARTYBOX_RESULTS', str(ROOT / 'partybox-sync-results.json')))
checks = []

def both(predicate, timeout=50):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:a,b = latest(),read_probe(OBSERVER)
        except (OSError,ValueError,AssertionError):time.sleep(.2);continue
        if predicate(a,b): return a,b
        time.sleep(.2)
    raise AssertionError({'host':latest().get('partyBoxes'),'observer':read_probe(OBSERVER).get('partyBoxes')})

def synchronized(row, identity):
    boxes=[b for b in row.get('partyBoxes',[]) if b['uuid']==identity]
    if len(boxes)!=1: return False
    box=boxes[0];audio=box.get('native') or {}
    sound=any(s.get('alState')==4114 and abs(s['x']-box['x'])<.02 and abs(s['y']-box['y']-.34)<.02 for s in row['sounds'])
    return box['playing'] and not audio.get('loading',True) and abs(audio.get('seconds',-1000)-box['seconds'])<1.5 and sound

def save(label,a,b=None):
    entry={'check':label,'result':'PASS','hostTick':a.get('tick'),'host':a.get('partyBoxes',a.get('partyBoxes',{}))}
    if b is not None:entry['observer']=b.get('partyBoxes')
    checks.append(entry);OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(checks,indent=2))
    print('PASS',label,flush=True)

if __name__=='__main__':
    both(lambda a,b:a['screen']==b['screen']=='none',90)
    command('partybox-demo youtube')
    a,b=both(lambda a,b:len(a.get('partyBoxes',[]))==len(b.get('partyBoxes',[]))==1)
    identity=a['partyBoxes'][0]['uuid']
    try:
        a,b=both(lambda a,b:synchronized(a,identity) and synchronized(b,identity))
        save('same_server_speaker_clock_and_real_audio_on_both_clients',a,b)
        before=a['partyBoxes'][0]['seconds'];tick=a['tick']
        command('observer-away')
        a,b=both(lambda a,b:not b.get('partyBoxes') and not b['sounds'])
        save('listener_unload_releases_entity_and_decoder',a,b)
        time.sleep(3)
        command('observer-return')
        a,b=both(lambda a,b:synchronized(a,identity) and synchronized(b,identity) and b['partyBoxes'][0]['seconds']>before+3)
        save('listener_reload_resumes_current_server_position',a,b)
        command('all-away')
        a,b=both(lambda a,b:not a.get('partyBoxes') and not b.get('partyBoxes') and not a['sounds'] and not b['sounds'])
        deadline=time.monotonic()+50
        while time.monotonic()<deadline:
            try:
                server=json.loads((ROOT/'server-probe.json').read_text())
                if time.time()*1000-server['wall']<2500 and identity not in server['partyBoxes']:break
            except (ValueError,OSError):pass
            time.sleep(.25)
        else:raise AssertionError('The server did not actually unload the trolley chunk')
        save('real_server_chunk_unload_removes_the_speaker_entity',server)
        command('all-return')
        a,b=both(lambda a,b:synchronized(a,identity) and synchronized(b,identity))
        speaker=a['partyBoxes'][0]
        assert abs((speaker['seconds']-before)-(a['tick']-tick)/20)<.15
        save('server_deserialization_keeps_uuid_and_uninterrupted_clock',a,b)
    finally:
        command('all-return');command('observer-return');command('cell-demo-end')
    a,b=both(lambda a,b:not a.get('partyBoxes') and not b.get('partyBoxes') and not a['sounds'] and not b['sounds'])
    save('cleanup_leaves_no_ghost_speaker_or_audio',a,b)
