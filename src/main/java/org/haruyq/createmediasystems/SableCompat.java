package org.haruyq.createmediasystems;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Blocks on a Sable sub-level (a moving structure: a Create: Aeronautics ship, or any other mod built on Sable)
 * keep their block positions in a far-off plot, while players and sound play out where the structure really is.
 * Ranges, areas and speaker directions all stay in block space; the listener is brought into it, and the sound
 * out of it. Without Sable, both leave positions as they are.
 */
public final class SableCompat {
    private SableCompat() {
    }

    /** {@code global} as seen from the blocks around {@code pos}. */
    public static Vec3 toBlockSpace(Level level, BlockPos pos, Vec3 global) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, pos);
        return subLevel == null ? global : subLevel.logicalPose().transformPositionInverse(global);
    }

    /** Where the block-space position {@code local} is in the world now. */
    public static Vec3 toWorld(Level level, Vec3 local) {
        return toWorld(level, BlockPos.containing(local), local);
    }

    public static Vec3 toWorld(Level level, BlockPos anchor, Vec3 local) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, anchor);
        return subLevel == null ? local : subLevel.logicalPose().transformPosition(local);
    }

    public static Vec3 toRenderWorld(Level level, BlockPos anchor, Vec3 local) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, anchor);
        return subLevel instanceof ClientSubLevelAccess client
                ? client.renderPose().transformPosition(local) : toWorld(level, anchor, local);
    }

    public static Vec3 toRenderBlockSpace(Level level, BlockPos anchor, Vec3 global) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, anchor);
        return subLevel instanceof ClientSubLevelAccess client
                ? client.renderPose().transformPositionInverse(global) : toBlockSpace(level, anchor, global);
    }

    public static boolean mayInteract(Level level, Player player, BlockPos pos) {
        BlockPos world = BlockPos.containing(toWorld(level, pos, Vec3.atCenterOf(pos)));
        return level.mayInteract(player, world);
    }

    /**
     * The block-space box {@code local} in the world now, as the axis-aligned box around it (larger than the box
     * itself while the structure is turned).
     */
    public static AABB toWorld(Level level, AABB local) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, local.getCenter());
        if (subLevel == null) {
            return local;
        }
        AABB world = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 at = subLevel.logicalPose().transformPosition(new Vec3((corner & 1) == 0 ? local.minX : local.maxX,
                    (corner & 2) == 0 ? local.minY : local.maxY, (corner & 4) == 0 ? local.minZ : local.maxZ));
            world = world == null ? new AABB(at, at) : world.minmax(new AABB(at, at));
        }
        return world;
    }

    /** Distance between two block positions, either of which may be on a sub-level. */
    public static double distance(Level level, BlockPos a, BlockPos b) {
        return toWorld(level, a.getCenter()).distanceTo(toWorld(level, b.getCenter()));
    }
}
