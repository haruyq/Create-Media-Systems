package org.haruyq.createmediasystems.blocks.turntable.atlpa2;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where the turntable's parts are, for its block, its renderer and its models alike (tools/at_lpa2/gen_at_lpa2.py has
 * the same numbers). Its own space is that of HifiLayout, in sixteenths: x across from its left as one faces its
 * front, y up, z from its back to its front. At its scale a sixteenth is 28 mm of the real deck.
 */
public final class TurntableLayout {
    /** The clear plinth's bottom and top, on its feet; and the shut dust cover's top. */
    public static final double PLINTH_BOTTOM = 1.25, PLINTH_TOP = 2.32, COVER_TOP = 5.82;
    /** The platter's centre (the spindle), its radius, and the heights of its top and of a record's top on it. */
    public static final double CENTER_X = 6.5, CENTER_Z = 6.5, PLATTER_RADIUS = 5.4, PLATTER_BOTTOM = 2.72,
            PLATTER_TOP = 3.42, RECORD_TOP = 3.48;
    /** A 12-inch record's radius, and the radii of its first groove and of its run-out, where its sound ends. */
    public static final double RECORD_RADIUS = 5.45, LEAD_IN = 5.2, RUN_OUT = 2.2;
    /** The motor's pulley, at the back left: the belt runs from it round the platter's rim. */
    public static final double MOTOR_X = 2.9, MOTOR_Z = 1.7;
    /** The tonearm's pivot, and how far from it its stylus is (223.6 mm effective length). */
    public static final double PIVOT_X = 13.2, PIVOT_Y = 4.17, PIVOT_Z = 3.7, ARM_LENGTH = 8.0;
    /** How far the lift lever raises the arm's head (degrees about its pivot). */
    public static final float LIFT_DEGREES = 3.6f;
    /** The lift lever's pivot, and how far it turns up. */
    public static final double CUE_Y = 3.37, CUE_Z = 5.2;
    public static final float CUE_DEGREES = 28;
    /** The dust cover's hinge line (its hinges' back bottom edge, on top of their seats), and how far it opens. */
    public static final double HINGE_Y = 2.62, HINGE_Z = 0.5;
    public static final float COVER_DEGREES = 78;
    /** The control unit before the plinth, its keys on its front: its left, right, top, back and front. */
    public static final double UNIT_X0 = 7.6, UNIT_X1 = 15.7, UNIT_TOP = 1.8, UNIT_Z0 = 12.65, UNIT_Z1 = 15.8;
    /** The power, 33, 45 and start/stop keys on the control unit's front: each one's middle (x, y) and radius. */
    public static final double[][] KEYS = {{8.55, .9, .42}, {10.35, .9, .27}, {12.05, .9, .27}, {14.9, .9, .42}};
    /** How far above its key's middle the lamp of each of the first three keys is. */
    public static final double LAMP_RISE = .6;

    private static final double PIVOT_TO_SPINDLE = Math.hypot(CENTER_X - PIVOT_X, CENTER_Z - PIVOT_Z);
    /** The direction from the pivot to the spindle, in degrees from the arm at rest (+z) towards the platter (-x). */
    private static final double SPINDLE_BEARING = Math.toDegrees(Math.atan2(PIVOT_X - CENTER_X, CENTER_Z - PIVOT_Z));

    /** What can be pointed at and right-clicked. Each is a box in the turntable's space. */
    public enum Part {
        /** The plinth: nothing happens. */
        BASE(0.5, 0, 0.5, 15.5, PLINTH_TOP, 12.5),
        COVER(0.5, PLINTH_TOP, 0.5, 15.5, COVER_TOP, 12.5),
        /** The open cover, standing up behind the deck, as far as it is inside the block. */
        COVER_OPEN(0.5, COVER_TOP, 0, 15.5, 16, 2.5),
        /** Up to just over a record's top, so that a click lands on the groove it points at. */
        PLATTER(1.0, PLINTH_TOP, 1.0, 12.0, 3.52, 12.0),
        ARM(12.2, PLINTH_TOP, 1.0, 14.2, 5.02, 12.5),
        LIFT(14.2, PLINTH_TOP, 4.5, 15.5, 4.32, 7.6),
        /** The control unit's keys: each is the part of the unit it is on, out to the key's face. */
        POWER(UNIT_X0, 0, UNIT_Z0, 9.5, UNIT_TOP, 15.95),
        SPEED_33(9.5, 0, UNIT_Z0, 11.2, UNIT_TOP, 15.95),
        SPEED_45(11.2, 0, UNIT_Z0, 12.9, UNIT_TOP, 15.95),
        START_STOP(12.9, 0, UNIT_Z0, UNIT_X1, UNIT_TOP, 15.95);

        public final double x0, y0, z0, x1, y1, z1;

        Part(double x0, double y0, double z0, double x1, double y1, double z1) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
        }

        /** Whether it is there to point at with the cover {@code open} or shut. */
        public boolean shown(boolean open) {
            return switch (this) {
                case COVER -> !open;
                case COVER_OPEN, PLATTER, ARM, LIFT -> open;
                default -> true;
            };
        }

        boolean contains(Vec3 at) {
            double e = 1e-3;
            return at.x >= x0 - e && at.x <= x1 + e && at.y >= y0 - e && at.y <= y1 + e && at.z >= z0 - e && at.z <= z1 + e;
        }

        double volume() {
            return (x1 - x0) * (y1 - y0) * (z1 - z0);
        }

        public Component label() {
            return Component.translatable("create_media_systems.turntable.part." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static final Map<Direction, VoxelShape> OPEN = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> SHUT = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            OPEN.put(facing, shape(facing, true));
            SHUT.put(facing, shape(facing, false));
        }
    }

    private TurntableLayout() {
    }

    /** What is pointed at and in the way: every part there is with the cover as it is. */
    public static VoxelShape shape(Direction facing, boolean open) {
        Map<Direction, VoxelShape> shapes = open ? OPEN : SHUT;
        VoxelShape shape = shapes == null ? null : shapes.get(facing);
        if (shape != null) {
            return shape;
        }
        VoxelShape all = Shapes.empty();
        for (Part part : Part.values()) {
            if (part.shown(open)) {
                Vec3 a = HifiLayout.toBlock(facing, part.x0, part.y0, part.z0);
                Vec3 b = HifiLayout.toBlock(facing, part.x1, part.y1, part.z1);
                all = Shapes.or(all, Shapes.box(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
                        Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)));
            }
        }
        return all.optimize();
    }

    /**
     * The part at {@code inBlock} (a point on its outline, 0 to 1 in its block) of a turntable facing {@code facing}
     * with its cover {@code open} or shut: the smallest that holds it; null for none.
     */
    @Nullable
    public static Part partAt(Direction facing, boolean open, Vec3 inBlock) {
        Vec3 at = HifiLayout.toStack(facing, inBlock);
        Part found = null;
        for (Part part : Part.values()) {
            if (part.shown(open) && part.contains(at) && (found == null || part.volume() < found.volume())) {
                found = part;
            }
        }
        return found;
    }

    /** The turntable's own point (sixteenths) at {@code inBlock}. */
    public static Vec3 toTurntable(Direction facing, Vec3 inBlock) {
        return HifiLayout.toStack(facing, inBlock);
    }

    /** How far from the spindle (sixteenths) a point of the turntable's space is. */
    public static double radius(double x, double z) {
        return Math.hypot(x - CENTER_X, z - CENTER_Z);
    }

    /** How far through a record's side (0 at its first groove, 1 at its run-out) the groove at {@code radius} is. */
    public static double fractionAt(double radius) {
        return Math.clamp((LEAD_IN - radius) / (LEAD_IN - RUN_OUT), 0, 1);
    }

    public static double radiusAt(double fraction) {
        return LEAD_IN - Math.clamp(fraction, 0, 1) * (LEAD_IN - RUN_OUT);
    }

    /**
     * How far (degrees, from the arm at rest towards the platter) the arm is swung for its stylus to be
     * {@code radius} from the spindle: by the law of cosines in the triangle of pivot, spindle and stylus.
     */
    public static double armAngleFor(double radius) {
        double d = PIVOT_TO_SPINDLE, l = ARM_LENGTH;
        double cos = Math.clamp((d * d + l * l - radius * radius) / (2 * d * l), -1, 1);
        return SPINDLE_BEARING - Math.toDegrees(Math.acos(cos));
    }

    /** Where (x, z in sixteenths) the stylus is with the arm swung {@code degrees}. */
    public static double[] stylus(double degrees) {
        double a = Math.toRadians(degrees);
        return new double[] {PIVOT_X - ARM_LENGTH * Math.sin(a), PIVOT_Z + ARM_LENGTH * Math.cos(a)};
    }
}
