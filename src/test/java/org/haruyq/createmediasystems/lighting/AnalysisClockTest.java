package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnalysisClockTest {
    @Test
    void matchesSharedPositionAcrossDifferentRoundTrips() {
        AnalysisClock nearby = new AnalysisClock();
        AnalysisClock remote = new AnalysisClock();
        nearby.receive(300_000_000, 300_000_000, 300);
        remote.receive(600_000_000, 0, 300);
        assertEquals(0, nearby.upstreamMillis());
        assertEquals(300, remote.upstreamMillis());
        assertEquals(1_000, nearby.position(1_000_000_000, 0));
        assertEquals(1_000, remote.position(1_000_000_000, 0));
    }

    @Test
    void measuresLatencyBeforePlaybackAndSlewsBackwardCorrections() {
        AnalysisClock clock = new AnalysisClock();
        clock.receive(1_000_000_000L, 400_000_000, -1);
        assertEquals(300, clock.upstreamMillis());
        assertEquals(125, clock.position(1_000_000_000L, 125));

        clock.receive(2_000_000_000L, 1_400_000_000L, 1_700);
        assertEquals(3_000, clock.position(3_000_000_000L, 0));
        clock.receive(3_000_000_000L, 2_400_000_000L, 2_500);
        assertEquals(3_000, clock.position(3_000_000_000L, 0));
        assertEquals(3_900, clock.position(4_000_000_000L, 0));

        clock.receive(4_000_000_000L, 1_400_000_000L, 0);
        assertEquals(3_900, clock.position(4_000_000_000L, 0));
        assertEquals(300, clock.upstreamMillis());

        clock.reset();
        assertEquals(0, clock.upstreamMillis());
        assertEquals(25, clock.position(4_000_000_000L, 25));
    }
}
