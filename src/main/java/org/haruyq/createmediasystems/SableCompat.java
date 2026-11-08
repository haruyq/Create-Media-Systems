package org.haruyq.createmediasystems;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;

import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

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

    /**
     * Where {@code subLevel} is: as it is drawn this frame if {@code render} (on a client), else where it logically
     * is; null for no sub-level.
     */
    @Nullable
    public static Pose3dc pose(@Nullable SubLevelAccess subLevel, boolean render) {
        return subLevel == null ? null
                : render && subLevel instanceof ClientSubLevelAccess client ? client.renderPose() : subLevel.logicalPose();
    }

    /** {@link #pose(SubLevelAccess, boolean)} of the sub-level holding {@code pos}. */
    @Nullable
    public static Pose3dc pose(Level level, BlockPos pos, boolean render) {
        return pose(SableCompanion.INSTANCE.getContaining(level, pos), render);
    }

    /** The axis-aligned box around {@code box} with each of its corners moved by {@code move}. */
    public static AABB transform(AABB box, UnaryOperator<Vec3> move) {
        AABB result = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 at = move.apply(new Vec3((corner & 1) == 0 ? box.minX : box.maxX,
                    (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ));
            result = result == null ? new AABB(at, at) : result.minmax(new AABB(at, at));
        }
        return result;
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
        return subLevel == null ? local : transform(local, subLevel.logicalPose()::transformPosition);
    }

    /** Distance between two block positions, either of which may be on a sub-level. */
    public static double distance(Level level, BlockPos a, BlockPos b) {
        return toWorld(level, a.getCenter()).distanceTo(toWorld(level, b.getCenter()));
    }
}
