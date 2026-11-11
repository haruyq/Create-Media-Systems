package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CachedLightingAnalysisTest {
    private static final long MAX_MILLIS = 7_200_000;
    private static final int INTERVAL = 100;

    private static void picture(CachedLightingAnalysis.Timeline timeline, long pts, int left, int right) {
        timeline.picture(96, 54, index -> index % 96 < 48 ? left : right, pts);
    }

    private static void tone(CachedLightingAnalysis.Timeline timeline, double seconds, long pts) {
        byte[] stereo = LightingAnalyzerTest.tone(80, 12000 / 32767.0, seconds, false);
        timeline.sound(stereo, stereo.length / 4, 2, pts);
    }

    @Test
    void mergesSeparateTrackTimestampsWithoutAdvancingDelayedAudioOrVideo() {
        CachedLightingAnalysis.Timeline timeline = new CachedLightingAnalysis.Timeline(MAX_MILLIS, INTERVAL);
        picture(timeline, 1_000_000, 0xff0000, 0x0000ff);
        picture(timeline, 1_100_000, 0xff0000, 0x0000ff);
        picture(timeline, 1_200_000, 0x00ff00, 0x0000ff);
        tone(timeline, 1.5, 1_300_000);
        var frames = timeline.finish();
        assertEquals(18, frames.size(), "one every 100 ms through the end of the audio");
        assertFalse(frames.get(0).audio());
        assertFalse(frames.get(2).audio());
        assertTrue(frames.get(3).audio());
        assertTrue(frames.get(4).bass() > frames.get(4).high() * 2);
        assertEquals(0xff0000, frames.get(0).left());
        assertEquals(0x0000ff, frames.get(0).right());
        assertNotEquals(0x00ff00, frames.get(2).left(), "color transitions are smoothed");
        assertTrue(frames.get(11).video());
        assertFalse(frames.get(12).video(), "an ended video track must not leave colors available forever");
        assertEquals(0, frames.get(0).motionPhase());
        assertTrue(frames.get(5).motionPhase() > frames.get(4).motionPhase(), "the motion phase runs on");
    }

    @Test
    void boundsLongMedia() {
        CachedLightingAnalysis.Timeline timeline = new CachedLightingAnalysis.Timeline(MAX_MILLIS, INTERVAL);
        picture(timeline, 5_000_000, 0xffffff, 0xffffff);
        picture(timeline, 5_000_000 + MAX_MILLIS * 1000, 0xffffff, 0xffffff);
        assertEquals(MAX_MILLIS / INTERVAL, timeline.finish().size());
    }
}
