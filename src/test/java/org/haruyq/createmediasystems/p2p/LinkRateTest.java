package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LinkRateTest {
    @Test
    void pacesAtTheProbeLessHeadroomFollowsSlowDeliveriesAndRecoversToIt() {
        LinkRate rate = new LinkRate();
        assertEquals(LinkRate.INITIAL_BPS, rate.bps());
        assertFalse(rate.measured());
        // 2.5 MB in 1 s: 20 Mbit/s, paced at 80% of it.
        rate.probed(2_500_000, 1_000_000_000L);
        assertTrue(rate.measured());
        assertEquals(16_000_000, rate.bps());

        // 4 MB paced at 16 Mbit/s takes 2 s; 8 s means the connection manages 4 Mbit/s now.
        rate.delivered(4_000_000, 8_000_000_000L);
        assertEquals(3_600_000, rate.bps());
        rate.delivered(4_000_000, 12_500_000_000L);
        assertEquals(3_600_000, rate.bps(), "between on pace and behind, it holds");
        long before = rate.bps();
        rate.delivered(1_000_000, 2_000_000_000L);
        assertEquals(before + 360_000, rate.bps(), "on pace, a tenth more");
        for (int i = 0; i < 100; i++) rate.delivered(1_000_000, 100_000_000L);
        assertEquals(16_000_000, rate.bps(), "never past what the probe measured");

        rate.delivered(1, 1_000_000_000_000L);
        assertEquals(LinkRate.MIN_BPS, rate.bps());
        rate.delivered(0, 1);
        rate.delivered(1, 0);
        assertEquals(LinkRate.MIN_BPS, rate.bps());
    }

    @Test
    void anEmptyProbeKeepsTheInitialPace() {
        LinkRate rate = new LinkRate();
        rate.probed(0, 0);
        assertTrue(rate.measured());
        assertEquals(LinkRate.INITIAL_BPS, rate.bps());
    }
}
