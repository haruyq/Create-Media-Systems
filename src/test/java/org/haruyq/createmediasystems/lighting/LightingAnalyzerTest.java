package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.haruyq.createmediasystems.Rgb;
import org.haruyq.createmediasystems.playback.Pcm;

import org.junit.jupiter.api.Test;

class LightingAnalyzerTest {
    private static final long START = 10_000_000;

    static byte[] tone(double hz, double amplitude, double seconds, boolean opposite) {
        int count = (int) (Pcm.RATE * seconds);
        byte[] stereo = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            short value = (short) Math.round(32767 * amplitude * Math.sin(2 * Math.PI * hz * i / Pcm.RATE));
            short right = opposite ? (short) -value : value;
            stereo[i * 4] = (byte) value;
            stereo[i * 4 + 1] = (byte) (value >> 8);
            stereo[i * 4 + 2] = (byte) right;
            stereo[i * 4 + 3] = (byte) (right >> 8);
        }
        return stereo;
    }

    private static void offer(LightingAnalyzer analyzer, byte[] stereo, long pts) {
        analyzer.sound(stereo, stereo.length / 4, 2, pts);
    }

    private static LightingAnalyzer.Sound analyze(double hz) {
        LightingAnalyzer analyzer = new LightingAnalyzer();
        byte[] stereo = tone(hz, 0.3, 1, false);
        byte[] original = stereo.clone();
        offer(analyzer, stereo, START);
        assertArrayEquals(original, stereo);
        return analyzer.heard(START + 1_000_000);
    }

    @Test
    void separatesLowMidAndHighWithoutChangingTheAudio() {
        LightingAnalyzer.Sound low = analyze(80);
        assertTrue(low.bass() > low.mid() * 2);
        assertTrue(low.bass() > low.high() * 2);
        LightingAnalyzer.Sound mid = analyze(1000);
        assertTrue(mid.mid() > mid.bass() * 2);
        assertTrue(mid.mid() > mid.high() * 2);
        LightingAnalyzer.Sound high = analyze(10_000);
        assertTrue(high.high() > high.bass() * 2);
        assertTrue(high.high() > high.mid() * 2);
    }

    @Test
    void oppositeStereoPhasesStillHaveEnergy() {
        LightingAnalyzer analyzer = new LightingAnalyzer();
        offer(analyzer, tone(80, 0.3, 1, true), START);
        assertEquals(analyze(80).bass(), analyzer.heard(START + 1_000_000).bass(), 0.001);
    }

    @Test
    void monoAnalysisMatchesStereoWithoutChangingSamples() {
        byte[] stereo = tone(80, 0.3, 1, false);
        byte[] mono = new byte[stereo.length / 2];
        for (int i = 0; i < mono.length / 2; i++) {
            mono[i * 2] = stereo[i * 4];
            mono[i * 2 + 1] = stereo[i * 4 + 1];
        }
        byte[] original = mono.clone();
        LightingAnalyzer analyzer = new LightingAnalyzer();
        analyzer.sound(mono, mono.length / 2, 1, START);
        assertArrayEquals(original, mono);
        assertEquals(analyze(80).bass(), analyzer.heard(START + 1_000_000).bass(), 0.001);
    }

    @Test
    void waitsForPresentationTimeAndExpiresOrClearsOldFeatures() {
        LightingAnalyzer analyzer = new LightingAnalyzer();
        byte[] stereo = tone(80, 0.4, 0.1, false);
        offer(analyzer, stereo, START);
        assertNull(analyzer.heard(START - 1));
        assertNull(analyzer.heard(START + 19_999));
        assertTrue(analyzer.heard(START + 20_000).pulse() > 0.9);
        assertNull(analyzer.heard(START + 400_000));
        analyzer.clear();
        assertNull(analyzer.heard(START + 20_000));
        offer(analyzer, stereo, START + 5_000_000);
        assertNull(analyzer.heard(START + 200_000));
        assertNotNull(analyzer.heard(START + 5_020_000));
    }

    @Test
    void followsRepeatedBassHitsAndLetsTheirPulseDecay() {
        LightingAnalyzer analyzer = new LightingAnalyzer();
        byte[] hit = tone(80, 0.5, 0.08, false);
        byte[] silence = new byte[(int) (Pcm.RATE * 0.42) * 4];
        for (int i = 0; i < 8; i++) {
            long at = START + i * 500_000L;
            offer(analyzer, hit, at);
            offer(analyzer, silence, at + 80_000);
            LightingAnalyzer.Sound onset = analyzer.heard(at + 20_000);
            // A frame taken anywhere in the interval after the beat sees it whole.
            LightingAnalyzer.Sound held = analyzer.heard(at + 20_000 + AnalysisFrame.INTERVAL * 1000L - 1);
            LightingAnalyzer.Sound rest = analyzer.heard(at + 480_000);
            assertTrue(onset.pulse() > 0.8);
            assertEquals(1, held.pulse());
            assertTrue(rest.pulse() < 0.2);
            assertEquals(120, rest.bpm(), 3);
        }
    }

    @Test
    void aBeatShowsFromItsFrameAndDecaysWithoutRisingBeforeIt() {
        AnalysisFrame before = new AnalysisFrame(0, 0.8f, 0.8f, 0, 0, 0, 120, 0, 0, true, false);
        AnalysisFrame beat = new AnalysisFrame(100, 0.8f, 0.8f, 0, 0, 1, 120, 0, 0, true, false);
        assertEquals(0, before.blend(beat, 90).pulse());
        assertEquals(1, beat.at(100).pulse());
        assertEquals(0.85f, beat.at(120).pulse(), 0.001f);
    }

    @Test
    void takesIndependentSideColorsWithoutBlackBarsOrMinorWhiteDetails() {
        int width = 96;
        int height = 64;
        int[] colors = LightingAnalyzer.colors(width, height, index -> {
            int x = index % width;
            int y = index / width;
            if (y < 16 || y >= 48) {
                return 0;
            }
            if (y < 20) {
                return 0xffffff;
            }
            return x < width / 2 ? 0xf02010 : 0x1030f0;
        });
        assertArrayEquals(new int[]{0xf02010, 0x1030f0}, colors);
        assertArrayEquals(new int[]{0, 0}, LightingAnalyzer.colors(width, height, index -> 0));
        assertEquals(0x800080, Rgb.mix(0xff0000, 0x0000ff, 0.5f));
    }

    @Test
    void colorsTurnOverTimeAndExpireWhenNoPictureFollows() {
        LightingAnalyzer analyzer = new LightingAnalyzer();
        analyzer.picture(8, 8, index -> 0xff0000, START);
        assertEquals(new LightingAnalyzer.Colors(0xff0000, 0xff0000), analyzer.shown(START));
        analyzer.picture(8, 8, index -> 0x0000ff, START + 10_000);
        assertEquals(0xff0000, analyzer.shown(START + 10_000).left(), "pictures between two takes are skipped");
        analyzer.picture(8, 8, index -> 0x0000ff, START + 100_000);
        assertEquals(Rgb.mix(0xff0000, 0x0000ff, 0.5f), analyzer.shown(START + 100_000).left(),
                "half way over a picture half the ramp later");
        assertNotNull(analyzer.shown(START + 1_000_000));
        assertNull(analyzer.shown(START + 1_100_000), "no picture for a second: the colours are over");
        assertNull(analyzer.shown(START), "colours aren't shown before they were seen");
    }

    @Test
    void framesCarryTheirSoundAndColoursWithin() {
        assertNull(LightingAnalyzer.frame(0, null, null));
        AnalysisFrame silent = LightingAnalyzer.frame(500, null, new LightingAnalyzer.Colors(1, 2));
        assertFalse(silent.audio());
        assertTrue(silent.video());
        assertEquals(AnalysisFrame.DEFAULT_BPM, silent.bpm());
        AnalysisFrame fast = LightingAnalyzer.frame(500, new LightingAnalyzer.Sound(0, 1, 1, 1, 1, 1, 1000), null);
        assertTrue(fast.valid(), "a tempo past the frame's bounds is held to them");
    }
}
