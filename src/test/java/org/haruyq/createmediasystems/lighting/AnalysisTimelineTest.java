package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AnalysisTimelineTest {
    @Test
    void interpolatesTenHertzFramesAndRejectsLeadingOrLargeGaps() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        assertTrue(timeline.add(List.of(frame(0, 0), frame(100, 1)), false));
        assertEquals(0.5f, timeline.sample(50).level(), 1e-6);
        assertNull(timeline.sample(-1));
        assertNull(timeline.sample(500), "a stale frame must not be used beyond the fallback window");

        AnalysisTimeline sparse = new AnalysisTimeline();
        assertTrue(sparse.add(List.of(frame(0, 0), frame(1_000, 1)), false));
        assertNull(sparse.sample(200), "an interpolation hole larger than one frame must be unavailable");
    }

    @Test
    void rejectsInvalidOrReversedBatchesAtomically() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        assertTrue(timeline.add(List.of(frame(0, 0)), false));
        assertFalse(timeline.add(List.of(frame(100, 1), frame(50, 0)), false));
        assertEquals(0, timeline.end());
        assertFalse(timeline.add(List.of(new AnalysisFrame(100, Float.NaN, 0, 0, 0, 0, 120, 0, 0, true, false)), false));
        assertFalse(timeline.add(List.of(new AnalysisFrame(100, 0, 0, 0, 0, 0, Float.POSITIVE_INFINITY, 0, 0, true, false)), false));
        assertFalse(timeline.add(List.of(new AnalysisFrame(100, 0, 0, 0, 0, 0, 120, 0x1000000, 0, true, false)), false));
        assertEquals(0, timeline.end());
    }

    @Test
    void cachedTimelineIsPreparedBeforeLiveFallback() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        assertTrue(timeline.add(List.of(frame(0, 0), frame(100, 1)), true));
        assertTrue(timeline.prepared());
        assertEquals(0.5f, timeline.sample(50).level(), 1e-6);
        assertTrue(timeline.add(List.of(frame(200, 0)), false));
        assertEquals(200, timeline.end());
        assertEquals(0.5f, timeline.sample(50).level(), 1e-6, "cached frames win at the same timestamp");
        assertEquals(0, timeline.sample(200).level(), 1e-6, "live data fills an uncached range");
    }

    @Test
    void liveFallbackDoesNotCountAsPreparedCoverage() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        timeline.add(List.of(frame(0, 0), frame(100, 1)), true);
        timeline.add(List.of(frame(200, 0.5f)), false);
        assertTrue(timeline.prepared());
        assertEquals(0.5f, timeline.sample(200).level(), 1e-6);
        assertNull(timeline.samplePrepared(200), "fresh live data must not stop live uploads while the cache catches up");

        timeline.add(List.of(frame(200, 0.8f), frame(300, 0.6f)), true);
        assertEquals(0.8f, timeline.samplePrepared(200).level(), 1e-6);
    }

    @Test
    void clearingSessionLiveFramesRetainsPreparedCoverage() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        timeline.add(List.of(frame(0, 0), frame(100, 1)), true);
        timeline.add(List.of(frame(200, 0.5f)), false);
        assertEquals(0.5f, timeline.sample(200).level(), 1e-6);

        timeline.clearLive();
        assertEquals(-1, timeline.liveEnd());
        assertNull(timeline.sample(200));
        assertTrue(timeline.prepared());
        assertEquals(0.5f, timeline.sample(50).level(), 1e-6);
    }

    @Test
    void lateJoinUsesPublishedMotionPhaseWithoutReplayingEarlierFrames() {
        AnalysisFrame first = frame(10_000, 1).phase(6);
        AnalysisFrame second = frame(10_100, 0).phase(6.12);
        AnalysisTimeline existing = new AnalysisTimeline();
        existing.add(List.of(frame(0, 0), first, second), true);
        AnalysisTimeline joined = new AnalysisTimeline();
        joined.add(List.of(first, second), true);
        assertEquals(6.06, joined.sample(10_050).motionPhase(), 1e-6);
        assertEquals(existing.sample(10_050), joined.sample(10_050));
        assertEquals(6.1375, joined.sample(10_150).motionPhase(), 1e-6);
        assertFalse(joined.add(List.of(frame(10_200, 0).phase(Double.NaN)), true));
    }

    @Test
    void expiredBrightnessStillProvidesPhaseForOwnerHandoff() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        timeline.add(List.of(frame(1_000, 0).phase(4)), false);
        assertNull(timeline.sample(4_000));
        assertEquals(5.05, timeline.phaseAt(4_000), 1e-6);
        assertTrue(Double.isNaN(timeline.phaseAt(999)));

        timeline.add(List.of(frame(500, 1).phase(1)), true);
        assertEquals(5.05, timeline.phaseAt(4_000), 1e-6, "a stale cache must not replace the newer live phase");
        timeline.add(List.of(frame(4_000, 1).phase(timeline.phaseAt(4_000))), false);
        assertEquals(5.05, timeline.sample(4_000).motionPhase(), 1e-6);
    }

    @Test
    void continuesFromLastLocalFrameWhenServerEchoIsDelayed() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        timeline.add(List.of(frame(67_200, 0).phase(35.033855)), false);
        AnalysisFrame previous = frame(67_819, 0).phase(35.409673);
        assertEquals(35.296355, timeline.phaseAt(67_950), 1e-6);
        assertEquals(35.455523, AnalysisTimeline.continuePhase(timeline, previous, 67_950), 1e-6);
        assertEquals(35.296355, AnalysisTimeline.continuePhase(timeline, null, 67_950), 1e-6);

        timeline.add(List.of(frame(67_900, 0).phase(35.44)), true);
        assertEquals(35.4575, AnalysisTimeline.continuePhase(timeline, previous, 67_950), 1e-6);
    }

    @Test
    void sharedDelayInterpolatesLateLiveFramesWithoutDelayingPreparedMedia() {
        AnalysisTimeline timeline = new AnalysisTimeline();
        timeline.add(List.of(frame(0, 0), frame(100, 1)), false);
        assertNull(timeline.sample(800));
        assertEquals(0.5f, timeline.sample(800, 750).level(), 1e-6);
        timeline.add(List.of(frame(800, 0.8f), frame(900, 0.6f)), true);
        assertEquals(0.8f, timeline.sample(800, 750).level(), 1e-6);
        assertEquals(timeline.sample(850).motionPhase(), timeline.phaseAt(850, 750), 1e-6);
    }

    @Test
    void alignsPreparedAndLivePhasesWhileCacheUploadCatchesUp() {
        List<AnalysisFrame> original = List.of(frame(0, 0).phase(0), frame(100, 1).phase(0.12),
                frame(200, 0).phase(0.24), frame(300, 1).phase(0.36));
        List<AnalysisFrame> aligned = AnalysisTimeline.align(original, 150, 0.03);
        assertEquals(0.03, AnalysisTimeline.phaseAt(aligned, 150), 1e-6);
        assertEquals(-0.15, aligned.getFirst().motionPhase(), 1e-6);
        assertEquals(0, original.getFirst().motionPhase(), 1e-6, "alignment must not change the original next-track analysis");

        AnalysisTimeline timeline = new AnalysisTimeline();
        assertTrue(timeline.add(aligned.subList(0, 2), true));
        double phase = AnalysisTimeline.phaseAt(aligned, 250);
        assertTrue(timeline.add(List.of(frame(250, 1).phase(phase)), false));
        assertEquals(phase, timeline.sample(250).motionPhase(), 1e-6);
        assertTrue(timeline.add(aligned.subList(2, 4), true));
        assertEquals(phase, timeline.sample(250).motionPhase(), 1e-6, "prepared arrival must retain the live phase");
        assertFalse(timeline.add(List.of(frame(400, 0).phase(Double.NEGATIVE_INFINITY)), false));
    }

    private static AnalysisFrame frame(long millis, float level) {
        return new AnalysisFrame(millis, level, level, level, level, level, 120, 0x102030, 0x405060, true, true);
    }

    @Test
    void handoffKeepsPublishedCacheAndContinuesItsPhase() {
        AnalysisTimeline server = new AnalysisTimeline();
        AnalysisTimeline existing = new AnalysisTimeline();
        List<AnalysisFrame> first = List.of(frame(0, 0).phase(2), frame(100, 0).phase(2.035));
        assertTrue(server.publishPrepared(first));
        existing.add(server.after(-1, 64), true);
        assertTrue(server.publishPrepared(List.of(frame(0, 1).phase(10), frame(100, 1).phase(10.1),
                frame(200, 1).phase(10.2), frame(300, 0).phase(10.3))));
        assertEquals(first.getFirst(), server.samplePrepared(0));
        assertEquals(2.07, server.samplePrepared(200).motionPhase(), 1e-6);
        existing.add(server.after(100, 64), true);
        assertEquals(server.samplePrepared(250), existing.samplePrepared(250));
        existing.add(List.of(frame(0, 1).phase(20)), true);
        assertEquals(first.getFirst(), existing.samplePrepared(0));
        assertFalse(server.publishPrepared(List.of(frame(400, 0), frame(500, 0).phase(Double.NaN))));
        assertEquals(300, server.end());
    }
}
