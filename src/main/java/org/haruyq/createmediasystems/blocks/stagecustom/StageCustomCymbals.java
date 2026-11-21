package org.haruyq.createmediasystems.blocks.stagecustom;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Layout in the same metres and orientation as the five shells. */
public final class StageCustomCymbals {
    public enum Cymbal {
        HI_HAT, CRASH, RIDE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Voice {
        HAT_CLOSED(Cymbal.HI_HAT), HAT_OPEN(Cymbal.HI_HAT), HAT_PEDAL(Cymbal.HI_HAT),
        CRASH(Cymbal.CRASH), RIDE_BOW(Cymbal.RIDE), RIDE_BELL(Cymbal.RIDE);

        public final Cymbal cymbal;

        Voice(Cymbal cymbal) {
            this.cymbal = cymbal;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Plate(int diameter, double x, double y, double z, double tilt, double bellRadius,
                        double bellRise, double bowRise, double baseX, double baseZ, double mast, double spread, double legAngle) {
        public double radius() {
            return diameter * .0127;
        }

        public Vec3 normal() {
            return new Vec3(0, Math.cos(Math.toRadians(tilt)), Math.sin(Math.toRadians(tilt)));
        }

        public Vec3 center() {
            return new Vec3(x, y, z);
        }

        public double profile(double radial) {
            double r = Math.clamp(radial, 0, radius());
            double fraction = r / radius();
            double dome = Math.cos(Math.PI / 2 * Math.min(r / bellRadius, 1));
            return bowRise * (1 - fraction * fraction) + bellRise * dome * dome;
        }

        public Vec3 point(double u, double v, double h) {
            var axis = normal();
            return center().add(u, h * axis.y - v * axis.z, h * axis.z + v * axis.y);
        }
    }

    public enum Pedal { KICK, HI_HAT }
    public record Hit(Cymbal cymbal, float u, float v, double distance, Voice voice) {}
    public record PedalHit(Pedal pedal, double distance) {}
    private record Box(double[] from, double[] to) {
        AABB bounds() {
            return new AABB(from[0], from[1], from[2], to[0], to[1], to[2]);
        }
    }
    private record Throne(double x, double y, double z, double radius, double spread) {}
    private record Data(Map<String, Plate> plates, Box kickPedal, Box hatPedal, double[] kickHinge, double[] hatHinge,
                        double[] beaterAxle, Throne throne, double openGap) {}
    private static final Cymbal[] CYMBALS = Cymbal.values();
    private static final Data DATA = read();
    private static final List<AABB> BOUNDS = buildBounds();

    private StageCustomCymbals() {}

    public static Plate plate(Cymbal cymbal) {
        return DATA.plates().get(cymbal.id());
    }

    public static double openGap() {
        return DATA.openGap();
    }

    public static Vec3 hinge(Pedal pedal) {
        return point(pedal == Pedal.KICK ? DATA.kickHinge() : DATA.hatHinge());
    }

    public static Vec3 beaterAxle() {
        return point(DATA.beaterAxle());
    }

    private static Vec3 point(double[] v) {
        return new Vec3(v[0], v[1], v[2]);
    }

    static List<AABB> bounds() {
        return BOUNDS;
    }

    @Nullable
    public static Hit aim(Vec3 eye, Vec3 direction, double reach, boolean open) {
        Hit winner = null;
        for (Cymbal cymbal : CYMBALS) {
            Hit hit = intersect(cymbal, eye, direction, reach, open);
            if (hit != null && (winner == null || hit.distance() < winner.distance())) winner = hit;
        }
        return winner;
    }

    @Nullable
    private static Hit intersect(Cymbal cymbal, Vec3 eye, Vec3 ray, double reach, boolean open) {
        var disk = plate(cymbal);
        var axis = disk.normal();
        var tangent = new Vec3(0, -axis.z, axis.y);
        Vec3 start = eye.subtract(disk.center());
        double dh = ray.dot(axis);
        if (Math.abs(dh) < .06) return null;
        double u = start.x, v = start.dot(tangent), h = start.dot(axis);
        double du = ray.x, dv = ray.dot(tangent);
        double gap = cymbal == Cymbal.HI_HAT && open ? openGap() : 0;
        double distance = (gap - h) / dh;
        for (int i = 0; i < 6; i++) {
            double radial = Math.hypot(u + distance * du, v + distance * dv);
            distance = (gap + disk.profile(radial) - h) / dh;
        }
        double x = u + distance * du, z = v + distance * dv;
        double radial = Math.hypot(x, z);
        if (distance < 0 || distance > reach || radial > disk.radius() + .004
                || Math.abs(h + dh * distance - gap - disk.profile(radial)) > .018) return null;
        Voice voice = switch (cymbal) {
            case HI_HAT -> open ? Voice.HAT_OPEN : Voice.HAT_CLOSED;
            case CRASH -> Voice.CRASH;
            case RIDE -> radial <= disk.bellRadius() ? Voice.RIDE_BELL : Voice.RIDE_BOW;
        };
        return new Hit(cymbal, (float) (x / disk.radius()), (float) (z / disk.radius()), distance, voice);
    }

    @Nullable
    public static PedalHit aimPedal(Vec3 eye, Vec3 direction, double reach) {
        Vec3 limit = eye.add(direction.scale(reach));
        double kick = along(eye, limit, DATA.kickPedal());
        double hat = along(eye, limit, DATA.hatPedal());
        if (!Double.isFinite(kick) && !Double.isFinite(hat)) return null;
        return kick < hat ? new PedalHit(Pedal.KICK, kick) : new PedalHit(Pedal.HI_HAT, hat);
    }

    private static double along(Vec3 eye, Vec3 limit, Box box) {
        return box.bounds().clip(eye, limit).map(eye::distanceTo).orElse(Double.POSITIVE_INFINITY);
    }

    private static Data read() {
        String path = "/assets/create_media_systems/models/block/stage_custom/cymbal_layout.json";
        try (var stream = StageCustomCymbals.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing SBP2F5AZM cymbal layout");
            var layout = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Data.class);
            for (Cymbal cymbal : CYMBALS) {
                if (!layout.plates().containsKey(cymbal.id())) throw new IllegalStateException("Missing " + cymbal.id());
            }
            return layout;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to load " + path, e);
        }
    }

    private static List<AABB> buildBounds() {
        var result = new ArrayList<AABB>();
        result.add(DATA.kickPedal().bounds());
        result.add(DATA.hatPedal().bounds());
        Throne seat = DATA.throne();
        result.add(new AABB(seat.x - seat.radius, seat.y - .066, seat.z - seat.radius,
                seat.x + seat.radius, seat.y, seat.z + seat.radius));
        result.add(new AABB(seat.x - .020, 0, seat.z - .020, seat.x + .020, seat.y - .05, seat.z + .020));
        tripod(result, seat.x, seat.z, seat.spread, 90);
        for (Cymbal cymbal : CYMBALS) {
            Plate disk = plate(cymbal);
            result.add(new AABB(disk.baseX-.022, 0, disk.baseZ-.022, disk.baseX+.022, disk.mast+.034, disk.baseZ+.022));
            tripod(result, disk.baseX, disk.baseZ, disk.spread, disk.legAngle);
            // Readily target the complete lip and the bell without filling the space below the bowl.
            double radius = disk.radius();
            for (int i = 0; i < 9; i++) {
                double x0 = (2*i/9d-1) * radius, x1 = (2*(i+1)/9d-1) * radius;
                double near = x0 <= 0 && x1 >= 0 ? 0 : Math.min(Math.abs(x0), Math.abs(x1));
                double width = Math.sqrt(radius * radius - near * near);
                double top = disk.profile(near) + .008 + (cymbal == Cymbal.HI_HAT ? openGap() : 0);
                double bottom = cymbal == Cymbal.HI_HAT ? -.042 : -.011;
                var low = new AABB(disk.point(x0,-width,bottom), disk.point(x1,width,bottom));
                var high = new AABB(disk.point(x0,-width,top), disk.point(x1,width,top));
                result.add(low.minmax(high));
            }
        }
        return List.copyOf(result);
    }

    private static void tripod(List<AABB> result, double x, double z, double radius, double rotation) {
        for (int i = 0; i < 3; i++) {
            double angle = Math.toRadians(rotation + i * 120);
            double fx = x + radius * Math.cos(angle), fz = z + radius * Math.sin(angle);
            result.add(new AABB(fx - .022, 0, fz - .022, fx + .022, .047, fz + .022));
        }
    }
}
