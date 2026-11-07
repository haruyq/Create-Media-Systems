package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;

class LightingIlluminationTest {
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    @Test
    void newFacesAndRefreshesShareAFiniteBudget() {
        var budget = new LightingIllumination.TraceBudget();
        budget.reset();
        for (int i = 0; i < 4; i++) {
            assertTrue(budget.take(13, true));
        }
        assertFalse(budget.take(13, true));
        for (int i = 0; i < 12; i++) {
            assertTrue(budget.take(1, true));
        }
        assertFalse(budget.take(1, true));
        for (int i = 0; i < 32; i++) {
            assertTrue(budget.take(1, false));
        }
        assertFalse(budget.take(1, false));
        budget.reset();
        assertTrue(budget.take(13, true));
        assertTrue(budget.take(1, false));
    }

    @Test
    void lightOnlyReachesTheForwardConeWithinItsRange() {
        double tangent = Math.tan(Math.toRadians(12));
        assertEquals(1, LightingIllumination.exposure(new Vec3(0, 0, 10), FORWARD, tangent, 32, 0));
        assertEquals(0, LightingIllumination.exposure(new Vec3(0, 0, -10), FORWARD, tangent, 32, 0));
        assertEquals(0, LightingIllumination.exposure(new Vec3(0, 0, 33), FORWARD, tangent, 32, 0));
        assertEquals(0, LightingIllumination.exposure(new Vec3(4, 0, 10), FORWARD, tangent, 32, 0));
    }

    @Test
    void coneEdgesFadeContinuously() {
        double tangent = 0.2;
        float inner = LightingIllumination.exposure(new Vec3(1.4, 0, 10), FORWARD, tangent, 32, 0);
        float outer = LightingIllumination.exposure(new Vec3(1.9, 0, 10), FORWARD, tangent, 32, 0);
        assertTrue(inner > outer && inner < 1 && outer > 0);
        assertEquals(0, LightingIllumination.exposure(new Vec3(2, 0, 10), FORWARD, tangent, 32, 0));
    }

    @Test
    void aBodyIntersectingANarrowBeamReceivesLight() {
        Vec3 center = new Vec3(0.2, 0, 10);
        assertEquals(0, LightingIllumination.exposure(center, FORWARD, 0.01, 32, 0));
        assertTrue(LightingIllumination.exposure(center, FORWARD, 0.01, 32, 0.21) > 0.99);
    }

    @Test
    void lightDoesNotDimExistingLightingOrChangeSkylight() {
        int original = LightTexture.pack(4, 9);
        assertEquals(original, LightingIllumination.packedLight(original, 0));
        int lit = LightingIllumination.packedLight(original, 0.25f);
        assertEquals(15, LightTexture.block(lit));
        assertEquals(9, LightTexture.sky(lit));
        assertEquals(LightTexture.pack(15, 9), LightingIllumination.packedLight(lit, 0.01f));
    }
}
