package org.haruyq.createmediasystems.items.partybox.stage320;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PartyBoxSpecsTest {
    @Test void louderBassierAndLitDrainsFaster(){
        assertTrue(PartyBoxSpecs.drainPerTick(32,2,1)>PartyBoxSpecs.drainPerTick(18,0,0));
    }
    @Test void trolleyIsSlowerNearTheHandleAndHasATopSpeed(){
        assertEquals(0,PartyBoxSpecs.desiredSpeed(.01));assertTrue(PartyBoxSpecs.desiredSpeed(.2)<PartyBoxSpecs.desiredSpeed(2));
        assertEquals(PartyBoxSpecs.desiredSpeed(100),PartyBoxSpecs.desiredSpeed(1000));
    }
}
