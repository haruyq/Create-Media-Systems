package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.haruyq.createmediasystems.client.SurfaceLight;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
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
                // Far out in the world, a sample must still land on the face's own corners, to the block's depth.
                float[][] at = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                for (int i = 0; i < 4; i++) {
                    Vec3 sampled = LightingIllumination.sample(corners, at[i][0], at[i][1], offset);
                    assertTrue(corners[i].add(offset).distanceTo(sampled) < 1e-6, "corner " + i);
                }
                Vec3 middle = corners[0].add(corners[1]).add(corners[2]).add(corners[3]).scale(0.25).add(offset);
                assertTrue(middle.distanceTo(LightingIllumination.sample(corners, 0.5f, 0.5f, offset)) < 1e-6);
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
        int stride = SurfaceLight.FORMAT.getVertexSize();
        assertEquals(28, stride);
        assertEquals(stride * 16, packed.length);
        try (var memory = new ByteBufferBuilder(packed.length)) {
            var out = new BufferBuilder(memory, VertexFormat.Mode.QUADS, SurfaceLight.FORMAT);
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
    void aFaceJustBehindAnotherBlocksSameOutlineIsHidden() {
        List<LightingIllumination.Face> faces = new ArrayList<>();
        // Neighbouring seats' shared armrest: this seat's top, and the next one's a hair higher over the same area.
        faces.add(top(new BlockPos(0, 0, 0), 13.501 / 16, 18.501 / 16, 11.5 / 16, 2 / 16.0, 14 / 16.0));
        faces.add(top(new BlockPos(1, 0, 0), 13.499 / 16, 18.499 / 16, 11.55 / 16, 2 / 16.0, 14 / 16.0));
        // A face laid over another of its own block (an overlay) is not another block's: both stay.
        faces.add(top(new BlockPos(5, 0, 0), 5, 6, 1, 0, 1));
        faces.add(top(new BlockPos(5, 0, 0), 5, 6, 1, 0, 1));
        // A floor: thousands of faces in one plane, none over another.
        for (int x = 0; x < 100; x++) {
            for (int z = 2; z < 102; z++) {
                faces.add(top(new BlockPos(x, 0, z), x, x + 1, 1, z, z + 1));
            }
        }
        boolean[] hidden = LightingIllumination.hidden(faces);
        assertTrue(hidden[0], "the lower armrest");
        assertFalse(hidden[1], "the one in front");
        assertFalse(hidden[2]);
        assertFalse(hidden[3]);
        for (int i = 4; i < faces.size(); i++) {
            assertFalse(hidden[i], "floor face " + i);
        }
    }

    /** An upward face over x0..x1 and z0..z1 at height y, of the block at {@code pos}. */
    private static LightingIllumination.Face top(BlockPos pos, double x0, double x1, double y, double z0, double z1) {
        Vec3[] points = {new Vec3(x0, y, z0), new Vec3(x1, y, z0), new Vec3(x1, y, z1), new Vec3(x0, y, z1)};
        // Counter-clockwise from above, the corners' own normal points down: the winding turns it up.
        return new LightingIllumination.Face(pos, Blocks.STONE.defaultBlockState(), 0, new int[32], points, new float[8],
                -1, 0xFFFFFF, Vec3.ZERO);
    }

    @Test
    void eachCornerOfACellFadesWithWhatSeesTheLampThere() {
        byte[] seen = new byte[13];
        Arrays.fill(seen, (byte) 1);
        for (int cell = 0; cell < 4; cell++) {
            assertEquals(0xFFFFFFFF, LightingIllumination.cellCorners(seen, cell % 2, cell / 2), "all lit");
        }
        // The face's first corner is cell (0, 0)'s first, and no other cell's.
        seen[0] = -1;
        assertEquals(0xFFFFFF80, LightingIllumination.cellCorners(seen, 0, 0));
        assertEquals(0xFFFFFFFF, LightingIllumination.cellCorners(seen, 1, 0));
        // Its middle in the dark too: every corner of it gets half, the blocked one none.
        seen[9] = -1;
        assertEquals(0x80808000, LightingIllumination.cellCorners(seen, 0, 0));
        // The face's middle is a corner of all four cells: the third of (0, 0), the fourth of (1, 0), the second of
        // (0, 1) and the first of (1, 1).
        seen[4] = 0;
        assertEquals(0x80008000, LightingIllumination.cellCorners(seen, 0, 0));
        assertEquals(0x80FFFFFF, LightingIllumination.cellCorners(seen, 1, 0));
        assertEquals(0xFFFF80FF, LightingIllumination.cellCorners(seen, 0, 1));
        assertEquals(0xFFFFFF80, LightingIllumination.cellCorners(seen, 1, 1));
        Arrays.fill(seen, (byte) -1);
        for (int cell = 0; cell < 4; cell++) {
            assertEquals(0, LightingIllumination.cellCorners(seen, cell % 2, cell / 2), "none of it lit: not drawn");
        }
    }

    @Test
    void newFacesAndRefreshesShareAFiniteBudget() {
        var budget = new LightingIllumination.TraceBudget();
        budget.reset();
        int taken = 0;
        while (budget.take(13, true)) {
            assertTrue(++taken < 1000, "a frame's budget runs out");
        }
        assertTrue(taken > 0);
        assertTrue(budget.take(1, false), "looking ahead has a budget of its own");
        budget.reset();
        assertTrue(budget.take(13, true), "each frame starts afresh");
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
