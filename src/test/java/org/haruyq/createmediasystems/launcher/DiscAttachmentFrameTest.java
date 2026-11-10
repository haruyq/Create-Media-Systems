package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DiscAttachmentFrameTest {
    @Test void bodyAndHeadFramesRoundTripTheActualContactPoint() {
        for(float yaw:new float[]{0,90,175,-170})for(float pitch:new float[]{-70,0,50}) {
            var frame=new DiscAttachmentFrame(new Vec3(1234,100,-456),yaw,pitch,.6,1.8);
            var point=new Vec3(1234.2,101.4,-456.3);
            assertTrue(frame.world(frame.local(point)).distanceTo(point)<1e-9);
            var direction=new Vec3(.2,.1,1).normalize();
            assertTrue(frame.worldDirection(frame.localDirection(direction)).distanceTo(direction)<1e-9);
        }
    }
    @Test void aBodyTurnMovesTheSameBladeAroundTheTargetInsteadOfLeavingItInTheWorld() {
        var initial=new DiscAttachmentFrame(Vec3.ZERO,0,0,.6,1.8);
        Vec3 point=initial.local(new Vec3(0,1.1,-.26));
        var turned=new DiscAttachmentFrame(new Vec3(5,2,7),90,0,.6,1.8);
        assertTrue(turned.world(point).distanceTo(new Vec3(5.26,3.1,7))<1e-6);
    }
    @Test void crouchingKeepsTheAttachmentAtTheSameFractionOfTheBodyHeight() {
        var standing=new DiscAttachmentFrame(Vec3.ZERO,0,0,.6,1.8);
        var crouched=new DiscAttachmentFrame(Vec3.ZERO,0,0,.6,1.5);
        Vec3 local=standing.local(new Vec3(.26,1.2,0));
        assertEquals(1,crouched.world(local).y,1e-9);assertEquals(.26,crouched.world(local).x,1e-9);
    }
}
