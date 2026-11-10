package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpectrumTest {
    /** A second of {@code hz} at {@code amplitude} (1 is full scale) on the left, silence on the right. */
    private static Spectrum.Frame heard(double hz, double amplitude) {
        Spectrum spectrum = new Spectrum();
        byte[] stereo = new byte[Pcm.RATE * 4];
        for (int n = 0; n < Pcm.RATE; n++) {
            short value = (short) Math.round(32767 * amplitude * Math.sin(2 * Math.PI * hz * n / Pcm.RATE));
            stereo[n * 4] = (byte) value;
            stereo[n * 4 + 1] = (byte) (value >> 8);
        }
        spectrum.offer(stereo, Pcm.RATE, 1_000_000);
        return spectrum.sample(1_900_000);
    }

    @Test
    void showsATonesBandAndChannel() {
        Spectrum.Frame frame = heard(1000, 1);
        assertNotNull(frame);
        // 1 kHz is band 5 (31.5 Hz doubled five times); on one channel of two it is 6 dB down in the mix.
        assertEquals(0.9, frame.bands()[5], 0.03);
        for (int band = 0; band < Spectrum.BANDS; band++) {
            if (Math.abs(band - 5) > 1) {
                assertTrue(frame.bands()[band] < 0.2, "band " + band + ": " + frame.bands()[band]);
            }
        }
        // A sine's RMS is 3 dB below its peak.
        assertEquals(0.95, frame.left(), 0.02);
        assertEquals(0, frame.right());
    }

    @Test
    void quieterShowsLower() {
        assertEquals(heard(250, 1).bands()[3] - 20 / 60f, heard(250, 0.1).bands()[3], 0.03);
    }

    @Test
    void stereoWidthLeavesMonoAloneAndSpreadsTheRest() {
        Equalizer equalizer = new Equalizer();
        equalizer.set(new float[0], 2);
        // Left 1000, right 0: the middle stays 500, the side doubles from 500 to 1000.
        byte[] stereo = {(byte) 0xE8, 0x03, 0, 0};
        equalizer.process(stereo);
        assertEquals(1500, (short) (stereo[0] & 0xff | stereo[1] << 8));
        assertEquals(-500, (short) (stereo[2] & 0xff | stereo[3] << 8));
        byte[] mono = {(byte) 0xE8, 0x03, (byte) 0xE8, 0x03};
        equalizer.process(mono);
        assertEquals(1000, (short) (mono[0] & 0xff | mono[1] << 8));
        assertEquals(1000, (short) (mono[2] & 0xff | mono[3] << 8));
    }

    @Test
    void nothingIsHeardBeforeOrLongAfter() {
        Spectrum spectrum = new Spectrum();
        spectrum.offer(new byte[Pcm.RATE * 4], Pcm.RATE, 1_000_000);
        assertNull(spectrum.sample(900_000));
        assertNull(spectrum.sample(3_000_000));
    }
}
