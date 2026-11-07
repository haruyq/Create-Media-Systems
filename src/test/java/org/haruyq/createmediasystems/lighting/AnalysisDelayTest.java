package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalysisDelayTest {
    @Test
    void schedulesSharedDelayFromObservedLagWithoutRewindingPlayout() {
        AnalysisDelay delay = AnalysisDelay.INITIAL.increase(600, 2_000);
        assertEquals(new AnalysisDelay(200, 750, 3_000), delay);
        assertEquals(200, delay.at(2_999));
        assertEquals(475, delay.at(4_000));
        assertEquals(750, delay.at(5_000));
        double previous = 0 - delay.at(0);
        for (long millis = 1; millis <= 6_000; millis++) {
            double position = millis - delay.at(millis);
            assertTrue(position > previous);
            previous = position;
        }
        assertSame(delay, delay.increase(1_000, 4_999));
        assertEquals(new AnalysisDelay(750, 1_150, 6_000), delay.increase(1_000, 5_000));
    }

    @Test
    void ignoresInvalidReportsAndSmallChangesAndCapsDelay() {
        AnalysisDelay initial = AnalysisDelay.INITIAL;
        assertSame(initial, initial.increase(-1, 2_000));
        assertSame(initial, initial.increase(2_001, 2_000));
        assertSame(initial, initial.increase(600, 1_999));
        assertSame(initial, initial.increase(100, 2_000));
        AnalysisDelay maximum = initial.increase(2_000, 2_000);
        assertEquals(2_000, maximum.to());
        assertEquals(2_000, maximum.at(5_000));
        assertSame(maximum, maximum.increase(0, 10_000));
    }
}
