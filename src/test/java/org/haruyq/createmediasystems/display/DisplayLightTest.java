package org.haruyq.createmediasystems.display;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class DisplayLightTest {
    @Test
    void shadowSamplesCoverBothDimensionsAtEveryRayBudget() {
        var screen = new DisplayBlock.Screen(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1));
        for (int rays = 2; rays <= 24; rays++) {
            var targets = DisplayLight.shadeTargets(screen, 12, 8, 800_000 / rays);
            assertEquals(rays, targets.length);
            boolean[] columns = new boolean[rays];
            boolean[] rows = new boolean[rays];
            double x = 0, y = 0;
            for (var target : targets) {
                assertTrue(target.x > 0 && target.x < 12);
                assertTrue(target.y > 0 && target.y < 8);
                assertEquals(0.05, target.z, 1e-6);
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
            assertArrayEquals(targets, DisplayLight.shadeTargets(screen, 12, 8, 800_000 / rays));
        }
    }

    @Test
    void shadowSampleLimitsStillFollowScreenSizeAndRoomDensity() {
        var screen = new DisplayBlock.Screen(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1));
        assertEquals(6, DisplayLight.shadeTargets(screen, 1, 1, 0).length);
        assertEquals(24, DisplayLight.shadeTargets(screen, 64, 36, 0).length);
        assertEquals(2, DisplayLight.shadeTargets(screen, 64, 36, 1_000_000).length);
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
