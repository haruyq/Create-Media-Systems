package org.haruyq.createmediasystems.blocks.train;

import static org.haruyq.createmediasystems.blocks.train.AnnounceCondition.Trigger.AFTER_DEPARTURE;
import static org.haruyq.createmediasystems.blocks.train.AnnounceCondition.Trigger.ARRIVAL;
import static org.haruyq.createmediasystems.blocks.train.AnnounceCondition.Trigger.BEFORE_ARRIVAL;
import static org.haruyq.createmediasystems.blocks.train.AnnounceCondition.Trigger.DEPARTURE;
import static org.haruyq.createmediasystems.blocks.train.AnnounceCondition.Trigger.PROGRESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnnounceConditionTest {
    /** 20 s into a trip, 40% of the way, 45 s to go at the current speed. */
    private static final AnnounceCondition.Trip UNDERWAY = new AnnounceCondition.Trip(true, false, 20, 0.4, 45);
    private static final AnnounceCondition.Trip ARRIVED = new AnnounceCondition.Trip(false, true, 65, 1, Double.POSITIVE_INFINITY);
    private static final AnnounceCondition.Trip STOPPED = new AnnounceCondition.Trip(true, false, 20, 0.4, Double.POSITIVE_INFINITY);

    @Test
    void eachTriggerFiresAtItsPointOfTheTrip() {
        assertTrue(DEPARTURE.ready(0, UNDERWAY));
        assertTrue(AFTER_DEPARTURE.ready(20, UNDERWAY));
        assertFalse(AFTER_DEPARTURE.ready(21, UNDERWAY));
        assertTrue(PROGRESS.ready(40, UNDERWAY));
        assertFalse(PROGRESS.ready(41, UNDERWAY));
        assertTrue(BEFORE_ARRIVAL.ready(45, UNDERWAY));
        assertFalse(BEFORE_ARRIVAL.ready(44, UNDERWAY));
        assertFalse(ARRIVAL.ready(0, UNDERWAY));
    }

    @Test
    void onlyArrivalFiresOnArrivingAndNothingCountsDownWhileStopped() {
        assertTrue(ARRIVAL.ready(0, ARRIVED));
        for (AnnounceCondition.Trigger trigger : new AnnounceCondition.Trigger[] {DEPARTURE, AFTER_DEPARTURE, PROGRESS, BEFORE_ARRIVAL}) {
            assertFalse(trigger.ready(0, ARRIVED), trigger.name());
        }
        assertFalse(BEFORE_ARRIVAL.ready(600, STOPPED), "a stopped train has no arrival time");
    }
}
