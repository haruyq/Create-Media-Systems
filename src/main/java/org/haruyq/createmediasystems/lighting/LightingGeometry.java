package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.lighting.LightingBeams.Clip;

import net.minecraft.world.phys.Vec3;

final class LightingGeometry {
    static final double CLEARANCE = 0.002;

    private LightingGeometry() {
    }

    static Vec3 normalToLocal(Vec3 normal, Vec3 basisX, Vec3 basisY, Vec3 basisZ) {
        return new Vec3(normal.dot(basisX), normal.dot(basisY), normal.dot(basisZ)).normalize();
    }

    record Vertex(Vec3 point, float red, float green, float blue, float alpha) {
        Vertex(Vec3 point, int color, float alpha) {
            this(point, color >> 16 & 255, color >> 8 & 255, color & 255, alpha);
        }

        Vertex lerp(Vertex other, double amount) {
            float t = (float) amount;
            return new Vertex(point.lerp(other.point, amount), red + (other.red - red) * t,
                    green + (other.green - green) * t, blue + (other.blue - blue) * t,
                    alpha + (other.alpha - alpha) * t);
        }
    }

    static int outsideCorners(Clip plane, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        return plane == null ? 0 : outsideCorner(plane, a) + outsideCorner(plane, b)
                + outsideCorner(plane, c) + outsideCorner(plane, d);
    }

    private static int outsideCorner(Clip plane, Vec3 at) {
        return plane.distance(at) >= CLEARANCE ? 0 : 1;
    }

    static List<Vertex> clip(List<Vertex> polygon, Clip plane) {
        if (plane == null || polygon.isEmpty()) {
            return polygon;
        }
        List<Vertex> result = new ArrayList<>(polygon.size() + 1);
        Vertex previous = polygon.getLast();
        double before = plane.distance(previous.point) - CLEARANCE;
        for (Vertex current : polygon) {
            double after = plane.distance(current.point) - CLEARANCE;
            if ((before >= 0) != (after >= 0)) {
                result.add(previous.lerp(current, before / (before - after)));
            }
            if (after >= 0) {
                result.add(current);
            }
            previous = current;
            before = after;
        }
        return result;
    }
}
