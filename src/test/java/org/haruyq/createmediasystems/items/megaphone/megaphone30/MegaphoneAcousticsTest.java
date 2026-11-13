package org.haruyq.createmediasystems.items.megaphone.megaphone30;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
class MegaphoneAcousticsTest {
    @Test void hornProjectsForwardAndTurnsWithItsHolder(){
        var forward=new Vec3(0,0,1);double front=MegaphoneAcoustics.direction(forward,new Vec3(0,0,20));
        assertTrue(front>7*MegaphoneAcoustics.direction(forward,new Vec3(0,0,-20)));
        assertTrue(front>4*MegaphoneAcoustics.direction(forward,new Vec3(20,0,0)));
        assertEquals(front,MegaphoneAcoustics.direction(new Vec3(1,0,0),new Vec3(20,0,0)),1e-9);
    }
    @Test void attenuationAndVolumeAreBounded(){assertEquals(0,MegaphoneAcoustics.gain(1,0,1));assertTrue(MegaphoneAcoustics.gain(60,32,1)<MegaphoneAcoustics.gain(6,32,1));assertTrue(MegaphoneAcoustics.gain(0,200,1)<=1);}
}
