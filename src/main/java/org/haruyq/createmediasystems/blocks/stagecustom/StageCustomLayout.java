package org.haruyq.createmediasystems.blocks.stagecustom;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Block-size metres. Origin at the centre of the root block's floor, with the drummer at +Z. */
public final class StageCustomLayout {
    public enum Drum {
        BASS, TOM_10, TOM_12, FLOOR, SNARE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Shell(String model, int diameter, double depth, int lugs, double x, double y, double z, double tilt) {
        public Vec3 head() {
            return new Vec3(x, y, z);
        }

        public Vec3 normal() {
            return new Vec3(0, Math.cos(Math.toRadians(tilt)), Math.sin(Math.toRadians(tilt)));
        }

        public Vec3 across() {
            var normal = normal();
            return new Vec3(0, -normal.z, normal.y);
        }

        public double diameterMetres() {
            return diameter * .0254;
        }

        public double depthMetres() {
            return depth * .0254;
        }

        public double radius() {
            return diameterMetres() / 2;
        }

        public double headRadius() {
            return radius() - .003;
        }

        public Vec3 point(double u, double v, double height) {
            return head().add(u, 0, 0).add(across().scale(v)).add(normal().scale(height));
        }
    }

    public record Hit(Drum drum, float u, float v, double distance) {}
    private record Foot(double[] from, double[] to) {}
    private record Data(Map<String, Shell> drums, List<Foot> feet) {}
    private static final Drum[] DRUMS = Drum.values();
    private static final Data DATA = read();
    private static final List<AABB> BOUNDS = outline();

    private StageCustomLayout() {}

    private static Data read() {
        String path = "/assets/create_media_systems/models/block/stage_custom/kit_layout.json";
        try (var stream = StageCustomLayout.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing SBP2F5-DUS layout");
            var data = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Data.class);
            for (Drum drum : DRUMS) {
                if (!data.drums().containsKey(drum.id())) throw new IllegalStateException("Missing " + drum.id() + " in " + path);
            }
            return data;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to load " + path, e);
        }
    }

    public static Shell shell(Drum drum) {
        return DATA.drums().get(drum.id());
    }

    static List<AABB> bounds() {
        return BOUNDS;
    }

    public static Vec3 toWorld(BlockPos root, Direction facing, Vec3 model) {
        Direction right = facing.getCounterClockWise();
        return Vec3.atBottomCenterOf(root).add(model.x * right.getStepX() + model.z * facing.getStepX(), model.y,
                model.x * right.getStepZ() + model.z * facing.getStepZ());
    }

    static Vec3 toModel(Direction facing, Vec3 relative) {
        Direction right = facing.getCounterClockWise();
        return new Vec3(relative.x * right.getStepX() + relative.z * right.getStepZ(), relative.y,
                relative.x * facing.getStepX() + relative.z * facing.getStepZ());
    }

    @Nullable
    public static Hit aim(Vec3 eye, Vec3 direction, double reach) {
        Hit nearest = null;
        for (Drum drum : DRUMS) {
            var hit = cylinder(drum, eye, direction);
            if (hit != null && hit.distance <= reach && (nearest == null || hit.distance < nearest.distance)) {
                nearest = hit;
            }
        }
        return nearest;
    }

    @Nullable
    private static Hit cylinder(Drum drum, Vec3 eye, Vec3 direction) {
        Shell shell = shell(drum);
        Vec3 start = eye.subtract(shell.head());
        Vec3 axis = shell.normal();
        Vec3 tangent = shell.across();
        double u = start.x, v = start.dot(tangent), h = start.dot(axis);
        double du = direction.x, dv = direction.dot(tangent), dh = direction.dot(axis);
        double radius = shell.radius() + .008, bottom = -shell.depthMetres();
        double at = Double.POSITIVE_INFINITY;
        boolean cap = false;
        if (Math.abs(dh) > 1e-9) {
            double surface = dh < 0 ? 0 : bottom;
            double t = (surface - h) / dh;
            double x = u + t * du, z = v + t * dv;
            if (t >= 0 && x * x + z * z <= radius * radius) {
                at = t;
                cap = true;
            }
        }
        double a = du * du + dv * dv;
        if (a > 1e-9) {
            double b = u * du + v * dv;
            double discriminant = b * b - a * (u * u + v * v - radius * radius);
            if (discriminant >= 0) {
                double t = (-b - Math.sqrt(discriminant)) / a;
                double along = h + t * dh;
                if (t >= 0 && t < at && along >= bottom && along <= 0) {
                    at = t;
                    cap = false;
                }
            }
        }
        if (!Double.isFinite(at)) return null;
        if (!cap || drum == Drum.BASS) return new Hit(drum, 0, 0, at);
        float x = (float) ((u + at * du) / shell.headRadius());
        float z = (float) ((v + at * dv) / shell.headRadius());
        double length = Math.max(1, Math.hypot(x, z) / .91);
        return new Hit(drum, (float) (x / length), (float) (z / length), at);
    }

    private static List<AABB> outline() {
        var bounds = new ArrayList<AABB>();
        for (var foot : DATA.feet()) {
            var a = foot.from;
            var b = foot.to;
            bounds.add(new AABB(a[0], a[1], a[2], b[0], b[1], b[2]));
        }
        // Rounded silhouette using chords, shared by placement, collisions and the block outline.
        for (Drum drum : DRUMS) {
            var shell = shell(drum);
            double radius = shell.radius() + (drum == Drum.BASS ? .015 : .011);
            for (int i = 0; i < 11; i++) {
                double minU = (2 * i / 11d - 1) * radius, maxU = (2 * (i + 1) / 11d - 1) * radius;
                double nearU = minU <= 0 && maxU >= 0 ? 0 : Math.min(Math.abs(minU), Math.abs(maxU));
                double span = Math.sqrt(radius * radius - nearU * nearU);
                Vec3 lowerA = shell.point(minU, -span, -shell.depthMetres() - .012);
                Vec3 lowerB = shell.point(minU, span, -shell.depthMetres() - .012);
                Vec3 upperA = shell.point(maxU, -span, .012);
                Vec3 upperB = shell.point(maxU, span, .012);
                bounds.add(new AABB(lowerA, lowerB).minmax(new AABB(upperA, upperB)));
            }
        }
        return List.copyOf(bounds);
    }
}
