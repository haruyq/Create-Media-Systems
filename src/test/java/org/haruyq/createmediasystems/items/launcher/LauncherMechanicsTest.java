package org.haruyq.createmediasystems.items.launcher;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LauncherMechanicsTest {
    @Test void fullSizeCartridgeFitsTheWellWithClearanceOnEverySide() {
        assertTrue(LauncherMechanics.DOCK_X - LauncherMechanics.CELL_HALF > LauncherMechanics.WELL_LEFT + .3f);
        assertTrue(LauncherMechanics.DOCK_X + LauncherMechanics.CELL_HALF < LauncherMechanics.WELL_RIGHT - .3f);
        assertTrue(LauncherMechanics.DOCK_Z - LauncherMechanics.CELL_HALF > LauncherMechanics.WELL_FRONT + .3f);
        assertTrue(LauncherMechanics.DOCK_Z + LauncherMechanics.CELL_HALF < LauncherMechanics.WELL_BACK - .3f);
        assertTrue(LauncherMechanics.DOCK_Y + LauncherMechanics.CELL_BOTTOM > LauncherMechanics.DOOR_Y + .4f);
        assertTrue(LauncherMechanics.DOCK_Y + LauncherMechanics.CELL_TOP < LauncherMechanics.LID_BOTTOM - .4f);
        assertTrue(LauncherMechanics.DOCK_Z + LauncherMechanics.CELL_HALF < LauncherMechanics.GRIP_FRONT);
    }

    @Test void cartridgeOnlyCrossesTheBottomWhenTheDoorsAreFullyOpen() {
        for (float age = 0; age < LauncherTimeline.RELOAD_TICKS; age += .125f) {
            var motion = LauncherTimeline.sample(age, -1);
            var cell = LauncherMechanics.cellPose(age, motion);
            assertEquals(LauncherMechanics.DOCK_X, cell.x());
            assertEquals(LauncherMechanics.DOCK_Z, cell.z());
            float bottom = cell.y() + LauncherMechanics.CELL_BOTTOM, top = cell.y() + LauncherMechanics.CELL_TOP;
            if (bottom < LauncherMechanics.DOOR_Y && top > LauncherMechanics.DOOR_Y - .65f) {
                assertEquals(1, motion.tray(), 1e-6, "Cell hits door at " + age);
                assertEquals(1, motion.latch(), 1e-6);
            }
            if (age >= LauncherTimeline.HAND_OUT.end()) assertEquals(0, motion.supportHand());
            if (age > LauncherTimeline.LATCH_CLOSES.start()) assertEquals(LauncherMechanics.DOCK_Y, cell.y(), 1e-6);
        }
        var outside = LauncherMechanics.cellPose(31, LauncherTimeline.sample(31, -1));
        assertTrue(outside.y() + LauncherMechanics.CELL_TOP < LauncherMechanics.DOOR_Y - .65f);
    }

    @Test void reloadFullyOpensBeforeFeedingAndClosesBeforeTheShotIsUnlocked() {
        var open = LauncherTimeline.sample(LauncherTimeline.OLD_CELL_OUT.end(), -1);
        assertEquals(1, open.latch(), 1e-6); assertEquals(1, open.tray(), 1e-6);
        assertEquals(1, open.cellOut(), 1e-6); assertEquals(0, open.disc(), 1e-6);
        assertTrue(open.glow() > .5f);
        var fed = LauncherTimeline.sample(LauncherTimeline.NEW_CELL_IN.end(), -1);
        assertEquals(1, fed.disc(), 1e-6); assertEquals(1, fed.latch(), 1e-6);
        var done = LauncherTimeline.sample(LauncherTimeline.RELOAD_TICKS, -1);
        assertEquals(0, done.latch()); assertEquals(0, done.glow()); assertEquals(0, done.supportHand());
    }
}
