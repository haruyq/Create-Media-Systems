package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.FloatBuffer;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class LightingBeamsTest {
    @Test
    void gpuPlaneKeepsTheSameSideAndClearanceAsCpuClipping() {
        Vec3 hit = new Vec3(-12.5, 6.25, 19);
        var plane = LightingGeometry.Plane.toward(hit, new Vec3(1, 2, -3), new Vec3(-4, 8, 2));
        FloatBuffer data = FloatBuffer.allocate(8);
        LightingBeams.plane(data, 4, plane);
        for (double offset : new double[] {-2, 0, 0.001, 0.003, 4}) {
            Vec3 point = hit.add(plane.normal().scale(offset));
            double distance = data.get(4) * point.x + data.get(5) * point.y + data.get(6) * point.z + data.get(7);
            assertEquals(plane.distance(point) - LightingGeometry.CLEARANCE, distance, 0.000002);
            assertEquals(plane.distance(point) >= LightingGeometry.CLEARANCE, distance >= 0);
        }
    }

    @Test
    void missingHitLeavesTheBeamUnclippedAndClearsAReusedRow() {
        FloatBuffer data = FloatBuffer.wrap(new float[] {2, 3, 4, -10});
        LightingBeams.plane(data, 0, null);
        assertArrayEquals(new float[] {0, 0, 0, 1}, data.array());
    }
}
