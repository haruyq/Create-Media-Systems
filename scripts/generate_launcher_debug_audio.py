#!/usr/bin/env python3
"""Generate the stereo diagnostic track used by the opt-in launcher fixture."""
import math
from pathlib import Path
import struct
import wave

target = Path(__file__).resolve().parents[1] / 'run-launcher-debug/media/disc-test.wav'
target.parent.mkdir(parents=True, exist_ok=True)
rate = 48_000
with wave.open(str(target), 'wb') as out:
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(rate)
    for second in range(60):
        block = bytearray()
        for sample in range(rate):
            t = second + sample / rate
            gain = .8 + .2 * math.sin(2 * math.pi * 2 * t)
            left = int(10000 * gain * (math.sin(2 * math.pi * 440 * t) + .2 * math.sin(2 * math.pi * 880 * t)))
            right = int(10000 * gain * (math.sin(2 * math.pi * 660 * t) + .2 * math.sin(2 * math.pi * 1320 * t)))
            block.extend(struct.pack('<hh', left, right))
        out.writeframes(block)
print(target)
