package org.haruyq.createmediasystems.blocks.display;

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
        assertFalse(refresh.begin(1_000), "one scan at a time");
        refresh.finish(true);
        long next = nextScan(refresh, 1_000);
        refresh.finish(true);
        assertEquals(-1, nextScan(refresh, next), "and nothing more once it is seen");
    }

    @Test
    void failedScansRetryWithoutStartingEveryFrame() {
        var refresh = new DisplayLight.Refresh();
        assertTrue(refresh.begin(100));
        refresh.finish(false);
        assertFalse(refresh.begin(101), "not on the next frame");
        long retried = nextScan(refresh, 101);
        assertTrue(retried > 101, "but later");
        refresh.finish(false);
        assertTrue(refresh.begin(0), "a world whose time went back starts over");
    }

    /** The first tick after {@code from}, within ten minutes, on which a scan begins; -1 if none does. */
    private static long nextScan(DisplayLight.Refresh refresh, long from) {
        for (long tick = from; tick < from + 12_000; tick++) {
            if (refresh.begin(tick)) {
                return tick;
            }
        }
        return -1;
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
