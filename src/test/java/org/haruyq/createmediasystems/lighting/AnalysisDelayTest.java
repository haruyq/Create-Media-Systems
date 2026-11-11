package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalysisDelayTest {
    private static final long SETTLED = AnalysisDelay.RAMP_MILLIS;

    @Test
    void schedulesSharedDelayFromObservedLagWithoutRewindingPlayout() {
        AnalysisDelay delay = AnalysisDelay.INITIAL.increase(600, SETTLED);
        assertEquals(AnalysisDelay.INITIAL.to(), delay.from(), "it turns from where it was");
        assertTrue(delay.to() >= 600 && delay.to() <= AnalysisDelay.MAX_MILLIS, "to cover the lag: " + delay);
        assertTrue(delay.start() > SETTLED, "not at once");
        assertEquals(delay.from(), delay.at(delay.start() - 1));
        assertEquals(delay.to(), delay.at(delay.start() + AnalysisDelay.RAMP_MILLIS));
        double previous = 0 - delay.at(0);
        for (long millis = 1; millis <= delay.start() + 2 * AnalysisDelay.RAMP_MILLIS; millis++) {
            double position = millis - delay.at(millis);
            assertTrue(position > previous, "played out backwards at " + millis);
            previous = position;
        }
        long settled = delay.start() + AnalysisDelay.RAMP_MILLIS;
        assertSame(delay, delay.increase(1_000, settled - 1), "a change stands before the next");
        AnalysisDelay longer = delay.increase(1_000, settled);
        assertEquals(delay.to(), longer.from());
        assertTrue(longer.to() >= 1_000, longer.toString());
    }

    @Test
    void ignoresInvalidReportsAndSmallChangesAndCapsDelay() {
        AnalysisDelay initial = AnalysisDelay.INITIAL;
        assertSame(initial, initial.increase(-1, SETTLED));
        assertSame(initial, initial.increase(AnalysisDelay.MAX_MILLIS + 1, SETTLED));
        assertSame(initial, initial.increase(600, SETTLED - 1));
        assertSame(initial, initial.increase(initial.to() / 2, SETTLED), "a lag the delay covers already");
        AnalysisDelay maximum = initial.increase(AnalysisDelay.MAX_MILLIS, SETTLED);
        assertEquals(AnalysisDelay.MAX_MILLIS, maximum.to());
        assertEquals(AnalysisDelay.MAX_MILLIS, maximum.at(maximum.start() + AnalysisDelay.RAMP_MILLIS));
        assertSame(maximum, maximum.increase(0, 10 * SETTLED));
    }
}
