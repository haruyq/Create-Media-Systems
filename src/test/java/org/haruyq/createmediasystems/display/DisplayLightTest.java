package org.haruyq.createmediasystems.display;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class DisplayLightTest {
    @Test
    void unchangedRoomsDoNotRescanAsTimeOrVideoFramesAdvance() {
        var refresh = new DisplayLight.Refresh();
        assertTrue(refresh.begin(0));
        refresh.finish(true);
        for (long tick = 1; tick <= 12000; tick++) {
            assertFalse(refresh.begin(tick));
        }
        refresh.invalidate();
        assertTrue(refresh.begin(12001));
    }

    @Test
    void changesDuringAScanAreKeptForTheNextScan() {
        var refresh = new DisplayLight.Refresh();
        assertTrue(refresh.begin(0));
        refresh.invalidate();
        assertFalse(refresh.begin(20));
        refresh.finish(true);
        assertTrue(refresh.begin(20));
        refresh.finish(true);
        assertFalse(refresh.begin(40));
    }

    @Test
    void failedScansRetryWithoutStartingEveryFrame() {
        var refresh = new DisplayLight.Refresh();
        assertTrue(refresh.begin(100));
        refresh.finish(false);
        for (long tick = 100; tick < 120; tick++) {
            assertFalse(refresh.begin(tick));
        }
        assertTrue(refresh.begin(120));
        refresh.finish(false);
        assertTrue(refresh.begin(0));
    }

    @Test
    void updatesOutsideTheScannedRoomDoNotTouchItsBounds() {
        var bounds = new DisplayLight.Bounds(new BlockPos(-20, -10, -30), new BlockPos(20, 10, 30));
        assertTrue(bounds.intersects(20, 0, 0, 21, 0, 0));
        assertTrue(bounds.intersects(-21, 0, 0, -20, 0, 0));
        assertTrue(bounds.intersects(-32, Integer.MIN_VALUE, -32, -17, Integer.MAX_VALUE, -17));
        assertFalse(bounds.intersects(21, 0, 0, 30, 0, 0));
        assertFalse(bounds.intersects(0, 11, 0, 0, 12, 0));
        assertFalse(bounds.intersects(0, 0, -40, 0, 0, -31));
    }
}
