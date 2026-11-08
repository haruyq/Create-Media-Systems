package org.haruyq.createmediasystems.playback;

import javax.sound.sampled.AudioFormat;

/** The audio every CMS stream hands the sound engine: 48 kHz, signed 16-bit little-endian, mono so OpenAL can place it. */
final class Pcm {
    static final int RATE = 48_000;
    static final int BYTES_PER_SECOND = RATE * 2;
    static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private Pcm() {
    }

    static long bytes(double seconds) {
        return Math.round(seconds * BYTES_PER_SECOND / 2) * 2;
    }
}
