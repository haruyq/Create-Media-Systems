package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.Arrays;
import java.util.List;

import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;

/** A separate voice mixer using the same sample bank as the playable grand piano. */
public final class GrandPianoSampler {
    private static final PianoAudio.Position OPEN_LID = new PianoAudio.Position(1, 0, true);

    private final PianoSynth synth = new PianoSynth();
    private final PianoSamples samples;
    private final int rate;
    private final float[] left, right;

    GrandPianoSampler(PianoSamples samples, int rate, int bufferFrames) {
        this.samples = samples;
        this.rate = rate;
        left = new float[bufferFrames];
        right = new float[bufferFrames];
    }

    public void accept(List<PianoEvent> events) {
        for (var event : events) synth.accept(event, samples);
    }

    /** Writes the requested number of stereo frames as signed, little-endian PCM. */
    public void render(byte[] pcm, int offset, int frames) {
        Arrays.fill(left, 0, frames, 0);
        Arrays.fill(right, 0, frames, 0);
        synth.render(left, right, frames, rate, OPEN_LID);
        for (int i = 0; i < frames; i++) {
            PianoAudio.write(pcm, offset + i * 4, left[i]);
            PianoAudio.write(pcm, offset + i * 4 + 2, right[i]);
        }
    }
}
