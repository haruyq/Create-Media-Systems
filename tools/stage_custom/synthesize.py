#!/usr/bin/env python3
"""Generate original, mono one-shots for the five SBP2F5-DUS drums."""
import subprocess
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SOUNDS = ROOT / "src/main/resources/assets/create_media_systems/sounds/stage_custom"
RATE = 48000
DRUMS = {
    "bass": (54, .82, .74),
    "tom_10": (172, .57, .71),
    "tom_12": (132, .64, .74),
    "floor": (83, .81, .77),
    "snare": (198, .60, .74),
}


def noise_band(rng, frames, low, high):
    samples = rng.standard_normal(frames)
    frequency = np.fft.rfftfreq(frames, 1/RATE)
    highpass = frequency / np.hypot(frequency, low)
    lowpass = 1 / np.sqrt(1 + (frequency/high)**6)
    noise = np.fft.irfft(np.fft.rfft(samples) * highpass**2 * lowpass, n=frames)
    return noise / np.sqrt(np.mean(noise * noise))


def mode(time, f, decay, chirp, phase=0, travel=.032):
    angle = np.pi*2 * (time*f + chirp*travel*(-np.expm1(-time/travel))) + phase
    return np.sin(angle) * np.exp(-time/decay)


def hit(name, variant):
    fundamental, duration, peak = DRUMS[name]
    rng = np.random.default_rng(2217 + tuple(DRUMS).index(name) * 1455 + variant*17)
    f = fundamental * (1 + rng.uniform(-.022, .021))
    time = np.arange(round(RATE*duration)) / RATE
    if name == "bass":
        sound = .89*mode(time,f,.152,49) + .20*mode(time,1.59*f,.069,61,.24)
        sound += .082*mode(time,2.13*f,.081,26,.73)
        sound += .082*noise_band(rng,len(time),680,5300)*np.exp(-time/.010)
    elif name == "snare":
        sound = (.30*mode(time,f,.082,23,.25) + .17*mode(time,1.59*f,.048,26,.72)
                 + .11*mode(time,2.14*f,.039,11))
        # The wire band excited by the lower head blooms just after the stick transient.
        wire = noise_band(rng,len(time),910,6600)
        sound += .28*wire*np.exp(-time/.109)*(-np.expm1(-time/.0023))
        sound += .13*noise_band(rng,len(time),220,2800)*np.exp(-time/.042)
        sound += .095*noise_band(rng,len(time),2800,9800)*np.exp(-time/.008)
    else:
        decay = {"tom_10":.126, "tom_12":.153, "floor":.209}[name]
        sound = (.83*mode(time,f,decay,f*.175) + .25*mode(time,1.59*f,decay*.57,f*.19,.34)
                 + .17*mode(time,2.135*f,decay*.44,f*.205,.57) + .072*mode(time,2.65*f,decay*.32,f*.07,1.3))
        sound += .085*noise_band(rng,len(time),950,6300)*np.exp(-time/.015)
    attack = np.minimum(time / .0006, 1)
    tail = np.clip((duration - time) / .068, 0, 1)
    sound *= attack * tail * tail * (3 - 2*tail)
    return sound * peak / np.max(np.abs(sound))


def main():
    SOUNDS.mkdir(parents=True, exist_ok=True)
    for name in DRUMS:
        for variant in range(3):
            pcm = np.round(hit(name,variant)*32767).astype("<i2").tobytes()
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "s16le", "-ar", str(RATE),
                            "-ac", "1", "-i", "pipe:0", "-c:a", "libvorbis", "-q:a", "4",
                            str(SOUNDS / f"{name}_{variant+1}.ogg")], input=pcm, check=True)


if __name__ == "__main__":
    main()
