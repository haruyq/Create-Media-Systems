package org.haruyq.createmediasystems.launcher;

/** Dimensions in model pixels. The renderer and support hand share this one cartridge path. */
public final class LauncherMechanics {
    public static final float CELL_HALF = 2.6f, CELL_BOTTOM = -3.6f, CELL_TOP = 4.1f;
    public static final float DOCK_X = 8, DOCK_Y = 11.2f, DOCK_Z = 11.8f;
    public static final float WELL_LEFT = 4.9f, WELL_RIGHT = 11.1f;
    public static final float WELL_FRONT = 8.8f, WELL_BACK = 14.9f;
    public static final float DOOR_Y = 7.1f, LID_BOTTOM = 15.75f, GRIP_FRONT = 16.1f;
    public static final float TRAVEL = 12.2f;

    private LauncherMechanics() {}

    public record CellPose(float x, float y, float z, boolean incoming) {}

    public static CellPose cellPose(float reloadAge, LauncherTimeline.Frame motion) {
        if (reloadAge < 0 || reloadAge >= LauncherTimeline.RELOAD_TICKS)
            return new CellPose(DOCK_X, DOCK_Y, DOCK_Z, false);
        boolean incoming = reloadAge >= LauncherTimeline.INCOMING_AT;
        float travel = incoming ? 1 - motion.disc() : motion.cellOut();
        return new CellPose(DOCK_X, DOCK_Y - TRAVEL * travel, DOCK_Z, incoming);
    }
}
