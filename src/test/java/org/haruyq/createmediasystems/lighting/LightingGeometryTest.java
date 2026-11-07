package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class LightingGeometryTest {
    private static final Vec3 SOURCE = Vec3.ZERO;

    @Test
    void clipsTheFullGlowWidthAtAnObliqueWall() {
        LightingGeometry.Plane wall = LightingGeometry.Plane.toward(new Vec3(0, 0, 5), new Vec3(0, 0, -1), SOURCE);
        List<LightingGeometry.Vertex> ribbon = polygon(new Vec3(0, 0, -0.06), new Vec3(0, 0, 0.06),
                new Vec3(5, 0, 5.06), new Vec3(5, 0, 4.94));
        List<LightingGeometry.Vertex> clipped = LightingGeometry.clip(ribbon, wall);
        assertFalse(clipped.isEmpty());
        assertTrue(clipped.stream().allMatch(vertex -> vertex.point().z <= 5 - LightingGeometry.CLEARANCE + 1e-9));
        assertTrue(clipped.stream().anyMatch(vertex -> vertex.point().z > 4.99));
    }

    @Test
    void aMiddleHitPlaneStopsAWedgeWhoseOuterRaysMiss() {
        List<LightingGeometry.Vertex> fan = polygon(SOURCE, new Vec3(-2, 0, 10), new Vec3(2, 0, 10));
        LightingGeometry.Plane pillar = LightingGeometry.Plane.toward(new Vec3(0, 0, 4), new Vec3(0, 0, -1), SOURCE);
        List<LightingGeometry.Vertex> clipped = LightingGeometry.clip(fan, pillar);
        assertTrue(clipped.size() >= 3);
        assertTrue(clipped.stream().allMatch(vertex -> vertex.point().z <= 4 - LightingGeometry.CLEARANCE + 1e-9));
    }

    @Test
    void respectsBothWallsAtACorner() {
        LightingGeometry.Plane wallX = LightingGeometry.Plane.toward(new Vec3(3, 0, 0), new Vec3(-1, 0, 0), SOURCE);
        LightingGeometry.Plane wallZ = LightingGeometry.Plane.toward(new Vec3(0, 0, 5), new Vec3(0, 0, -1), SOURCE);
        List<LightingGeometry.Vertex> fan = polygon(SOURCE, new Vec3(6, 0, 3), new Vec3(2, 0, 8));
        List<LightingGeometry.Vertex> clipped = LightingGeometry.clip(LightingGeometry.clip(fan, wallX), wallZ);
        assertTrue(clipped.size() >= 3);
        for (LightingGeometry.Vertex vertex : clipped) {
            assertTrue(wallX.distance(vertex.point()) >= LightingGeometry.CLEARANCE - 1e-9);
            assertTrue(wallZ.distance(vertex.point()) >= LightingGeometry.CLEARANCE - 1e-9);
        }
    }

    @Test
    void interpolatesColorAndOpacityAtTheCut() {
        LightingGeometry.Plane wall = new LightingGeometry.Plane(new Vec3(0.5, 0, 0), new Vec3(-1, 0, 0));
        List<LightingGeometry.Vertex> triangle = List.of(
                new LightingGeometry.Vertex(new Vec3(0, 0, 0), 0x0000FF, 0),
                new LightingGeometry.Vertex(new Vec3(1, 0, 0), 0xFF0000, 200),
                new LightingGeometry.Vertex(new Vec3(0, 1, 0), 0x0000FF, 0));
        LightingGeometry.Vertex cut = LightingGeometry.clip(triangle, wall).stream()
                .filter(vertex -> vertex.point().x > 0.49).findFirst().orElseThrow();
        assertEquals(255 * 0.498, cut.red(), 0.0001);
        assertEquals(255 * 0.502, cut.blue(), 0.0001);
        assertEquals(200 * 0.498, cut.alpha(), 0.0001);
    }

    @Test
    void fullyBlockedPolygonsProduceNoGeometry() {
        LightingGeometry.Plane wall = LightingGeometry.Plane.toward(new Vec3(0, 0, 2), new Vec3(0, 0, -1), SOURCE);
        assertTrue(LightingGeometry.clip(polygon(new Vec3(-1, 0, 3), new Vec3(1, 0, 3), new Vec3(0, 1, 4)), wall).isEmpty());
    }

    @Test
    void normalsAreOrientedTowardTheLightSource() {
        LightingGeometry.Plane wall = LightingGeometry.Plane.toward(new Vec3(0, 0, 5), new Vec3(0, 0, 1), SOURCE);
        assertTrue(wall.distance(SOURCE) > 0);
        assertEquals(-1, wall.normal().z);
    }

    @Test
    void transformedPlanesRetainTheirTangentUnderScaleAndShear() {
        Vec3 worldNormal = new Vec3(1, 1, 0).normalize();
        Vec3 localNormal = LightingGeometry.normalToLocal(worldNormal, new Vec3(2, 0, 0),
                new Vec3(0.5, 3, 0), new Vec3(0, 0, 1));
        assertEquals(0, localNormal.dot(new Vec3(1, -4 / 7.0, 0)), 1e-9);
        assertEquals(1, localNormal.length(), 1e-9);
    }

    private static List<LightingGeometry.Vertex> polygon(Vec3... points) {
        return java.util.Arrays.stream(points).map(point -> new LightingGeometry.Vertex(point, 0xFFFFFF, 100)).toList();
    }
}
