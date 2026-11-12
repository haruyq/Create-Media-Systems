package org.haruyq.createmediasystems.api.sublevel;

import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Blocks on a Sable sub-level (a moving structure) keep their positions in a far-off plot ("block space") while they
 * show where the structure really is. Without Sable every position is left as it is.
 */
@ApiStatus.Experimental
public final class SubLevels {
    private SubLevels() {
    }

    /** Whether {@code pos} is on a sub-level. */
    public static boolean onSubLevel(Level level, BlockPos pos) {
        return SableCompat.containing(level, pos) != null;
    }

    public static boolean sameSubLevel(Level level, BlockPos a, BlockPos b) {
        return SableCompat.sameSubLevel(level, a, b);
    }

    /** Where the block-space position {@code local}, near block {@code anchor}, is in the world now. */
    public static Vec3 toWorld(Level level, BlockPos anchor, Vec3 local) {
        return SableCompat.toWorld(level, anchor, local);
    }

    /** The block-space box {@code local} in the world now, as the axis-aligned box around it. */
    public static AABB toWorld(Level level, AABB local) {
        return SableCompat.toWorld(level, local);
    }

    /** {@code global} as seen from the blocks around {@code anchor}. */
    public static Vec3 toBlockSpace(Level level, BlockPos anchor, Vec3 global) {
        return SableCompat.toBlockSpace(level, anchor, global);
    }

    /** As {@link #toWorld(Level, BlockPos, Vec3)}, where the structure is drawn this frame (client). */
    public static Vec3 toRenderWorld(Level level, BlockPos anchor, Vec3 local) {
        return SableCompat.toRenderWorld(level, anchor, local);
    }

    /** As {@link #toBlockSpace}, where the structure is drawn this frame (client). */
    public static Vec3 toRenderBlockSpace(Level level, BlockPos anchor, Vec3 global) {
        return SableCompat.toRenderBlockSpace(level, anchor, global);
    }

    /** Where {@code entity}'s eye is in the world this frame, carried along by a sub-level it rides. */
    public static Vec3 eye(Entity entity, float partialTick) {
        return SableCompat.eye(entity, partialTick);
    }

    /** Level#mayInteract at where {@code pos} really is. */
    public static boolean mayInteract(Level level, Player player, BlockPos pos) {
        return SableCompat.mayInteract(level, player, pos);
    }

    public static double distance(Level level, BlockPos a, BlockPos b) {
        return SableCompat.distance(level, a, b);
    }
}
