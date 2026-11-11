package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import org.haruyq.createmediasystems.client.mesh.CellLight;
import org.junit.jupiter.api.Test;

class CellLightingTest {
    @Test void depletionTurnsOffEmissionAndDistanceBoundsItsReach() {
        assertEquals(0, CellLighting.strength(0));
        assertEquals(0, CellLighting.strength(-1));
        assertEquals(1, CellLighting.strength(1));
        assertTrue(CellLighting.strength(.01f) > 0);
        assertEquals(0, CellLight.falloff(18, 18));
        assertEquals(0, CellLight.falloff(30, 18));
        assertTrue(CellLight.falloff(2, 18) > CellLight.falloff(10, 18));
    }

    @Test void actualTierColorsGiveDifferentReflectedHues() {
        int pink = DiscCellTier.RESONANT.color, blue = DiscCellTier.DIAMOND.color;
        int pr = CellLight.channel(255, pink >> 16 & 255, 1), pg = CellLight.channel(255, pink >> 8 & 255, 1);
        int br = CellLight.channel(255, blue >> 16 & 255, 1), bb = CellLight.channel(255, blue & 255, 1);
        assertTrue(pr > pg + 70);
        assertTrue(bb > br + 70);
        assertEquals(147, CellLight.channel(147, 0, 0));
    }
}
