package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.LauncherTimeline;

/** The optic centre is (8,18.9,6.5) model pixels. Full ADS places it exactly on the camera's forward axis. */
public record LauncherGunPose(float x,float y,float z,float pitch,float yaw,float roll,float scale) {
    public static final float SCALE=.66f,OPTIC_Y=18.9f,OPTIC_Z=6.5f;
    public static LauncherGunPose sample(LauncherTimeline.Frame reload,float hand,float aim,float kick,float bob) {
        aim=Math.clamp(aim,0,1);
        float y=-.40f+(-((OPTIC_Y/16-.5f)*SCALE)+.40f)*aim;
        return new LauncherGunPose(hand*(.29f*(1-aim)+reload.x()),
                y+reload.y()+bob*(1-aim)*.35f,
                -1.05f+.05f*aim+reload.z()+kick*.020f,
                reload.pitch()+kick*(1.8f-.7f*aim),hand*reload.yaw(),hand*reload.roll(),SCALE);
    }
}
