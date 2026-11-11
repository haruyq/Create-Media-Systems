package org.haruyq.createmediasystems.playback;

import javax.sound.sampled.AudioFormat;

/** The audio every CMS stream hands the sound engine: 48 kHz, signed 16-bit little-endian, mono so OpenAL can place it. */
public final class Pcm {
    public static final int RATE = 48_000;
    static final int BYTES_PER_SECOND = RATE * 2;
    static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private Pcm() {
    }

    static long bytes(double seconds) {
        return Math.round(seconds * BYTES_PER_SECOND / 2) * 2;
    }

    /**
     * Sample {@code frame} of {@code channel} (0 left, 1 right; a mono {@code pcm} has its one channel for both) in
     * 16-bit little-endian {@code pcm} of {@code channels} interleaved channels, from -1 to 1.
     */
    public static double sample(byte[] pcm, int frame, int channels, int channel) {
        int at = (frame * channels + Math.min(channel, channels - 1)) * 2;
        return (short) (pcm[at] & 0xff | pcm[at + 1] << 8) / 32768.0;
    }
}
