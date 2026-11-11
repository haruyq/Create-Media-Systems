package org.haruyq.createmediasystems.partybox;
import org.haruyq.createmediasystems.power.BatteryItem;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PartyBoxSpecsTest {
    @Test void nominalLightsOffVolumeMatchesTheRated18HourBudget(){
        assertEquals(BatteryItem.CAPACITY,PartyBoxSpecs.drainPerTick(18,0,0)*PartyBoxSpecs.RATED_TICKS,1e-6);
        assertTrue(PartyBoxSpecs.drainPerTick(32,2,1)>PartyBoxSpecs.drainPerTick(18,0,0));
    }
    @Test void trolleyIsSlowerNearTheHandleAndNeverExceedsWalkingSpeed(){
        assertEquals(0,PartyBoxSpecs.desiredSpeed(.01));assertTrue(PartyBoxSpecs.desiredSpeed(.2)<PartyBoxSpecs.desiredSpeed(2));
        assertEquals(.32,PartyBoxSpecs.desiredSpeed(100));
    }
}
