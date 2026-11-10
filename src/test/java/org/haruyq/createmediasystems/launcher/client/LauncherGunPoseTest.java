package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.LauncherTimeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LauncherGunPoseTest {
    @Test void bothHandsAimTheOpticExactlyAlongTheCameraAxis() {
        for(float hand:new float[]{-1,1}) {
            var p=LauncherGunPose.sample(LauncherTimeline.sample(-1,-1),hand,1,0,0);
            assertEquals(0,p.x(),1e-6);
            assertEquals(0,p.y()+(LauncherGunPose.OPTIC_Y/16-.5f)*p.scale(),1e-6);
            assertEquals(0,p.pitch(),1e-6);assertEquals(0,p.yaw(),1e-6);assertEquals(0,p.roll(),1e-6);
            assertTrue(p.z()+(LauncherGunPose.OPTIC_Z/16-.5f)*p.scale()<-.6f);
        }
    }
    @Test void hipFirePointsStraightForwardAndRecoilComesBackTowardTheShoulder() {
        var idle=LauncherGunPose.sample(LauncherTimeline.sample(-1,-1),1,0,0,0);
        var fired=LauncherGunPose.sample(LauncherTimeline.sample(-1,1),1,0,1,0);
        assertEquals(0,idle.yaw());assertEquals(0,idle.pitch());
        assertTrue(fired.z()>idle.z());assertTrue(fired.pitch()>0 && fired.pitch()<5);
        assertEquals(idle.scale(),fired.scale());
    }
    @Test void aimSettlesQuicklyWithoutOvershootAndRecoilReturnsToTheSamePose() {
        var motion=new LauncherGunMotion();float previous=0;
        for(int i=0;i<8;i++){motion.tick(true);float aim=motion.aim(1);assertTrue(aim>=previous && aim<=1);previous=aim;}
        assertTrue(motion.aim(1)>.98f);
        motion.shot();motion.tick(true);assertTrue(motion.kick(1)>.1f);
        for(int i=0;i<30;i++)motion.tick(true);
        assertTrue(motion.kick(1)<.001f);
        for(int i=0;i<10;i++)motion.tick(false);
        assertTrue(motion.aim(1)<.001f);
    }
}
