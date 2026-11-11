package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DopplerMathTest {
    private static double ratio(double sx, double svx, double lx, double ly, double lvx) {
        return DopplerMath.ratio(new Vec3(sx, 0, 0), new Vec3(svx, 0, 0), new Vec3(lx, ly, 0), new Vec3(lvx, 0, 0));
    }
    @Test void aPassingTrolleyRaisesAndThenLowersTheActualMusicPitch() {
        assertEquals(343.0 / 339, ratio(0,.2,10,0,0),1e-9);
        assertEquals(343.0 / 347, ratio(20,.2,10,0,0),1e-9);
    }
    @Test void thePullerAndAStationaryPairHaveNoPitchShift() {
        assertEquals(1,ratio(0,.2,1,1,.2),1e-9);
        assertEquals(1,ratio(0,0,10,0,0),1e-9);
    }
    @Test void aMovingListenerAlsoHearsDopplerFromAStationaryBox() {
        assertTrue(ratio(0,0,10,0,-.2)>1);
        assertTrue(ratio(0,0,10,0,.2)<1);
    }
    @Test void spawnAndTeleportStepsDoNotCreateAPitchSpike() {
        assertNull(DopplerMath.steady(new Vec3(512,0,0)));
        assertNull(DopplerMath.steady(new Vec3(Double.NaN,0,0)));
        assertEquals(new Vec3(.2,0,0),DopplerMath.steady(new Vec3(.2,0,0)));
    }
}
