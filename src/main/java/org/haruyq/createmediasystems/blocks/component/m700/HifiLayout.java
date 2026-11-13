package org.haruyq.createmediasystems.blocks.component.m700;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where a hi-fi stack's units and their controls are. A stack's own space is in sixteenths of its block: x across
 * from its left as one faces its front, y up, z towards the front, whichever way the block faces.
 */
public final class HifiLayout {
    private HifiLayout() {
    }

    /** How far up the unit at {@code index} of {@code units} (bottom first) starts. */
    public static float bottom(List<Unit> units, int index) {
        float bottom = 0;
        for (int i = 0; i < index; i++) {
            bottom += units.get(i).height;
        }
        return bottom;
    }

    public static float height(List<Unit> units) {
        return bottom(units, units.size());
    }

    /** A point of a block facing {@code facing}, given in the block (0 to 1), in the stack's space. */
    public static Vec3 toStack(Direction facing, Vec3 inBlock) {
        double angle = Math.toRadians(facing.toYRot());
        double x = inBlock.x - 0.5;
        double z = inBlock.z - 0.5;
        return new Vec3((x * Math.cos(angle) + z * Math.sin(angle) + 0.5) * 16, inBlock.y * 16,
                (-x * Math.sin(angle) + z * Math.cos(angle) + 0.5) * 16);
    }

    /** A point of the stack's space, in its block (0 to 1) facing {@code facing}. */
    public static Vec3 toBlock(Direction facing, double x, double y, double z) {
        double angle = Math.toRadians(-facing.toYRot());
        x = x / 16 - 0.5;
        z = z / 16 - 0.5;
        return new Vec3(x * Math.cos(angle) + z * Math.sin(angle) + 0.5, y / 16,
                -x * Math.sin(angle) + z * Math.cos(angle) + 0.5);
    }

    /**
     * A point on a face of the stack: {@code x} across from the face's left as one looks at it and {@code y} up
     * (both sixteenths, as a Control's place plus its unit's bottom), {@code out} in front of the face.
     */
    public static Vec3 onFace(Direction facing, boolean rear, double x, double y, double out) {
        return rear ? toBlock(facing, Unit.LEFT + Unit.WIDTH - x, y, Unit.BACK - out)
                : toBlock(facing, Unit.LEFT + x, y, Unit.FRONT + out);
    }

    /** Where a look crosses the front or the back of a stack: across that face from its left, and up (sixteenths). */
    public record Aim(boolean rear, double x, double y) {
    }

    /**
     * Where a look from {@code eye} along {@code look} (both given in the block, 0 to 1) crosses the plane of the
     * front of a stack facing {@code facing}, or of its back if it comes from behind; null if it runs along them.
     * Not kept to the face, so a knob held is still turned with the crosshair off the stack.
     */
    @Nullable
    public static Aim aim(Direction facing, Vec3 eye, Vec3 look) {
        Vec3 from = toStack(facing, eye);
        Vec3 along = toStack(facing, eye.add(look)).subtract(from);
        boolean rear = from.z < Unit.BACK;
        double plane = rear ? Unit.BACK : Unit.FRONT;
        if (!rear && from.z <= Unit.FRONT || Math.abs(along.z) < 1e-6 || (plane - from.z) / along.z <= 0) {
            return null;
        }
        Vec3 at = from.add(along.scale((plane - from.z) / along.z));
        return new Aim(rear, rear ? Unit.LEFT + Unit.WIDTH - at.x : at.x - Unit.LEFT, at.y);
    }

    @Nullable
    public static Control control(List<Unit> units, Aim aim) {
        float bottom = 0;
        for (Unit unit : units) {
            if (aim.y >= bottom && aim.y < bottom + unit.height) {
                return Control.at(unit, aim.rear, aim.x, aim.y - bottom);
            }
            bottom += unit.height;
        }
        return null;
    }
}
