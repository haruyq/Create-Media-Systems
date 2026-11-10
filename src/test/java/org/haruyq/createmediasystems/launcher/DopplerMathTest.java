package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DopplerMathTest {
    @Test void aPassingTrolleyRaisesAndThenLowersTheActualMusicPitch() {
        assertEquals(343.0 / 339, DopplerMath.observedRatio(0,0,0,.2,0,0,10,0,0,0,0,0),1e-9);
        assertEquals(343.0 / 347, DopplerMath.observedRatio(20,0,0,.2,0,0,10,0,0,0,0,0),1e-9);
    }
    @Test void thePullerAndAStationaryPairHaveNoPitchShift() {
        assertEquals(1,DopplerMath.observedRatio(0,0,0,.2,0,0,1,1,0,.2,0,0),1e-9);
        assertEquals(1,DopplerMath.observedRatio(0,0,0,0,0,0,10,0,0,0,0,0),1e-9);
    }
    @Test void aMovingListenerAlsoHearsDopplerFromAStationaryBox() {
        assertTrue(DopplerMath.observedRatio(0,0,0,0,0,0,10,0,0,-.2,0,0)>1);
        assertTrue(DopplerMath.observedRatio(0,0,0,0,0,0,10,0,0,.2,0,0)<1);
    }
    @Test void spawnAndTeleportStepsDoNotCreateAPitchSpike() {
        assertEquals(1,DopplerMath.observedRatio(0,0,0,512,0,0,10,0,0,0,0,0));
        assertEquals(1,DopplerMath.observedRatio(0,0,0,.2,0,0,10,0,0,512,0,0));
        assertEquals(1,DopplerMath.observedRatio(0,0,0,Double.NaN,0,0,10,0,0,0,0,0));
    }
}
