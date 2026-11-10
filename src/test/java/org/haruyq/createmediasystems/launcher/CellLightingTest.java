package org.haruyq.createmediasystems.launcher;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CellLightingTest {
    @Test void depletionTurnsOffEmissionAndDistanceBoundsItsReach() {
        assertEquals(0, CellLighting.strength(0));
        assertEquals(0, CellLighting.strength(-1));
        assertEquals(1, CellLighting.strength(1));
        assertTrue(CellLighting.strength(.01f) > 0);
        assertEquals(0, CellLighting.falloff(18, 18));
        assertEquals(0, CellLighting.falloff(30, 18));
        assertTrue(CellLighting.falloff(2, 18) > CellLighting.falloff(10, 18));
    }

    @Test void actualTierColorsGiveDifferentReflectedHues() {
        int pink = DiscCellTier.RESONANT.color, blue = DiscCellTier.DIAMOND.color;
        int pr = CellLighting.channel(255, pink >> 16 & 255, 1), pg = CellLighting.channel(255, pink >> 8 & 255, 1);
        int br = CellLighting.channel(255, blue >> 16 & 255, 1), bb = CellLighting.channel(255, blue & 255, 1);
        assertTrue(pr > pg + 70);
        assertTrue(bb > br + 70);
        assertEquals(147, CellLighting.channel(147, 0, 0));
    }
}
