package org.haruyq.createmediasystems.display;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class DisplayLightTest {
    @Test
    void shadowSamplesCoverBothDimensionsAtEveryRayBudget() {
        var screen = new DisplayBlock.Screen(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1));
        java.util.Set<Integer> counts = new java.util.HashSet<>();
        for (int points = 1; points <= 4_000_000; points *= 2) {
            var targets = DisplayLight.shadeTargets(screen, 12, 8, points);
            int rays = targets.length;
            counts.add(rays);
            boolean[] columns = new boolean[rays];
            boolean[] rows = new boolean[rays];
            double x = 0, y = 0;
            for (var target : targets) {
                assertTrue(target.x > 0 && target.x < 12);
                assertTrue(target.y > 0 && target.y < 8);
                assertTrue(target.z > 0 && target.z < 0.5, "just in front of the screen");
                int column = (int) (target.x / 12 * rays);
                int row = (int) (target.y / 8 * rays);
                assertFalse(columns[column]);
                assertFalse(rows[row]);
                columns[column] = true;
                rows[row] = true;
                x += target.x;
                y += target.y;
            }
            assertEquals(6, x / rays, 1e-6);
            assertEquals(4, y / rays, 1e-6);
            assertArrayEquals(targets, DisplayLight.shadeTargets(screen, 12, 8, points));
        }
        assertTrue(counts.size() > 3, "the room's density changes how many: " + counts);
    }

    @Test
    void shadowSampleLimitsStillFollowScreenSizeAndRoomDensity() {
        var screen = new DisplayBlock.Screen(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1));
        int small = DisplayLight.shadeTargets(screen, 1, 1, 0).length;
        int large = DisplayLight.shadeTargets(screen, 64, 36, 0).length;
        int crowded = DisplayLight.shadeTargets(screen, 64, 36, 1_000_000).length;
        assertTrue(large > small, "a larger screen samples its shadows from more points");
        assertTrue(crowded < large && crowded >= 2, "a crowded room fewer, but never one");
    }

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
