package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;

class LightingIlluminationTest {
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    @Test
    void surfaceSamplesKeepBilinearPositionsAndOcclusionOffsets() {
        Random random = new Random(42);
        for (int face = 0; face < 100; face++) {
            Vec3[] corners = new Vec3[4];
            for (int i = 0; i < corners.length; i++) {
                corners[i] = new Vec3(random.nextDouble(-30_000_000, 30_000_000), random.nextDouble(-64, 320),
                        random.nextDouble(-30_000_000, 30_000_000));
            }
            for (Vec3 offset : new Vec3[] {Vec3.ZERO, new Vec3(-0.0003, 0.0007, 0.0005)}) {
                for (int y = 0; y <= 4; y++) {
                    for (int x = 0; x <= 4; x++) {
                        float u = x / 4f, v = y / 4f;
                        Vec3 expected = corners[0].lerp(corners[1], u).lerp(corners[3].lerp(corners[2], u), v).add(offset);
                        assertEquals(expected, LightingIllumination.sample(corners, u, v, offset));
                    }
                }
            }
        }
    }

    @Test
    void cachedVerticesMatchMinecraftsVertexWriter() {
        Vec3[] samples = new Vec3[9];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new Vec3(i * 0.25 - 3, i / 3 * 0.5, -0.125);
        }
        Vec3 normal = new Vec3(-1.2, 0.58, -0.37);
        float[] uv = {0.125f, 0.125f, 0.875f, 0.125f, 0.875f, 0.875f, 0.125f, 0.875f};
        float[] texels = {0.125f, 0.5f, 0.875f};
        byte[] packed = LightingIllumination.vertices(samples, normal, uv, 0x3F81C2);
        int stride = LightingIllumination.FORMAT.getVertexSize();
        assertEquals(28, stride);
        assertEquals(stride * 16, packed.length);
        try (var memory = new ByteBufferBuilder(packed.length)) {
            var out = new BufferBuilder(memory, VertexFormat.Mode.QUADS, LightingIllumination.FORMAT);
            for (int i : new int[] {0, 1, 4, 3, 1, 2, 5, 4, 3, 4, 7, 6, 4, 5, 8, 7}) {
                Vec3 point = samples[i];
                out.addVertex((float) point.x, (float) point.y, (float) point.z).setUv(texels[i % 3], texels[i / 3])
                        .setColor(0x3F, 0x81, 0xC2, 255).setNormal((float) normal.x, (float) normal.y, (float) normal.z);
            }
            try (var mesh = out.buildOrThrow()) {
                var expected = mesh.vertexBuffer();
                for (int i = 0; i < packed.length; i++) {
                    if (i % stride != stride - 1) {
                        assertEquals(expected.get(i), packed[i], "vertex byte " + i);
                    }
                }
            }
        }
    }

    @Test
    void visibilityKeepsTheFourCellsAndTheirSharedCorners() {
        byte[] seen = new byte[13];
        Arrays.fill(seen, (byte) 1);
        for (int cell = 0; cell < 4; cell++) {
            seen[9 + cell] = 0;
            for (int other = 0; other < 4; other++) {
                assertEquals(cell != other, LightingIllumination.cellLit(seen, other % 2, other / 2));
            }
            seen[9 + cell] = 1;
        }
        seen[0] = -1;
        assertFalse(LightingIllumination.cellLit(seen, 0, 0));
        assertTrue(LightingIllumination.cellLit(seen, 1, 0));
        assertTrue(LightingIllumination.cellLit(seen, 0, 1));
        assertTrue(LightingIllumination.cellLit(seen, 1, 1));
        seen[4] = -1;
        for (int cell = 0; cell < 4; cell++) {
            assertFalse(LightingIllumination.cellLit(seen, cell % 2, cell / 2));
        }
    }

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
