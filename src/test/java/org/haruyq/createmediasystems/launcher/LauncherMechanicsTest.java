package org.haruyq.createmediasystems.launcher;

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
            if (age >= LauncherMechanics.HAND_END) assertEquals(0, motion.supportHand());
            if (age > 63) assertEquals(LauncherMechanics.DOCK_Y, cell.y(), 1e-6);
        }
        var outside = LauncherMechanics.cellPose(31, LauncherTimeline.sample(31, -1));
        assertTrue(outside.y() + LauncherMechanics.CELL_TOP < LauncherMechanics.DOOR_Y - .65f);
    }
}
