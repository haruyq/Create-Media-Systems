package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EqualizerTest {
    /** How loud {@code hz} comes out of {@code equalizer} on each channel (dB against what went in), once settled. */
    private static double[] gainDb(Equalizer equalizer, double hz) {
        int frames = Pcm.RATE;
        byte[] stereo = new byte[frames * 4];
        for (int n = 0; n < frames; n++) {
            short value = (short) Math.round(4000 * Math.sin(2 * Math.PI * hz * n / Pcm.RATE));
            for (int c = 0; c < 2; c++) {
                stereo[n * 4 + c * 2] = (byte) value;
                stereo[n * 4 + c * 2 + 1] = (byte) (value >> 8);
            }
        }
        equalizer.process(stereo);
        double[] db = new double[2];
        for (int c = 0; c < 2; c++) {
            double peak = 0;
            for (int n = frames / 2; n < frames; n++) {
                peak = Math.max(peak, Math.abs((short) (stereo[n * 4 + c * 2] & 0xff | stereo[n * 4 + c * 2 + 1] << 8)));
            }
            db[c] = 20 * Math.log10(peak / 4000);
        }
        return db;
    }

    @Test
    void raisesItsBandOnBothChannelsAndLeavesTheRest() {
        float[] bands = {707, 1414, 9};
        Equalizer equalizer = new Equalizer();
        equalizer.set(bands);
        for (double db : gainDb(equalizer, 1000)) {
            assertEquals(9, db, 0.2);
        }
        for (double db : gainDb(equalizer, 60)) {
            assertEquals(0, db, 0.3);
        }
    }

    @Test
    void bandsAddUpAndClipInsteadOfWrapping() {
        Equalizer equalizer = new Equalizer();
        equalizer.set(new float[]{707, 1414, -6, 707, 1414, -6});
        assertEquals(-12, gainDb(equalizer, 1000)[0], 0.3);
        equalizer.set(new float[]{707, 1414, 12, 707, 1414, 12}); // 4000 × 16 is far past a 16-bit sample
        assertEquals(20 * Math.log10(Short.MAX_VALUE / 4000.0), gainDb(equalizer, 1000)[0], 0.1);
    }
}
