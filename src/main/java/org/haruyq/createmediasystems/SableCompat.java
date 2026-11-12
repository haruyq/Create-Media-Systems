package org.haruyq.createmediasystems;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;

import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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

    /**
     * A block whose removal does what moving it must not: it drops the item it holds, unlinks what it is linked to or
     * lets go of what it holds. Sable removes the blocks it assembles a ship from, so each of these is listed in
     * data/sable/tags/block/silent_assembly_removal.json (BlockResourcesTest checks the list), which has it removed
     * without that; else the ship's copy and the dropped item would be two.
     */
    public interface SilentAssemblyRemoval {
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

    /** Both world raycasts and vanilla use packets can describe a hit on a plot. */
    public static Vec3 hitInBlockSpace(Level level, BlockPos block, Vec3 point) {
        var ship = containing(level, block);
        if (ship == null) return point;
        Vec3 projected = ship.logicalPose().transformPositionInverse(point);
        Vec3 center = block.getCenter();
        return point.distanceToSqr(center) <= projected.distanceToSqr(center) ? point : projected;
    }

    public static Vec3 hitInWorld(Level level, BlockHitResult hit) {
        BlockPos block = hit.getBlockPos();
        return toWorld(level, block, hitInBlockSpace(level, block, hit.getLocation()));
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

    /** The sub-level holding {@code pos}; null without one (or without Sable). */
    @Nullable
    public static SubLevelAccess containing(Level level, BlockPos pos) {
        return SableCompanion.INSTANCE.getContaining(level, pos);
    }

    public static boolean sameSubLevel(Level level, BlockPos a, BlockPos b) {
        return containing(level, a) == containing(level, b);
    }

    public static Iterable<? extends SubLevelAccess> subLevels(Level level) {
        return SableCompanion.INSTANCE.getAllIntersecting(level,
                new BoundingBox3d(-30_000_000, -30_000_000, -30_000_000, 30_000_000, 30_000_000, 30_000_000));
    }

    public static Iterable<? extends SubLevelAccess> subLevels(Level level, AABB bounds) {
        return SableCompanion.INSTANCE.getAllIntersecting(level,
                new BoundingBox3d(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ));
    }

    /** Whether {@code pos} lies in the far-off plots where sub-levels keep their blocks. */
    public static boolean inPlotGrid(Level level, BlockPos pos) {
        return SableCompanion.INSTANCE.isInPlotGrid(level, pos);
    }

    public static boolean inPlotGrid(Level level, Vec3 pos) {
        return SableCompanion.INSTANCE.isInPlotGrid(level, pos);
    }

    /** Where {@code entity}'s eye is in the world this frame, carried along by a sub-level it rides. */
    public static Vec3 eye(Entity entity, float partialTick) {
        return SableCompanion.INSTANCE.getEyePositionInterpolated(entity, partialTick);
    }

    @Nullable
    static Vec3 sharedStep(Entity first, Entity second) {
        var ship = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(first);
        if (ship == null || ship != SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(second)) return null;
        Vec3 local = ship.logicalPose().transformPositionInverse(second.position());
        return second.position().subtract(ship.lastPose().transformPosition(local));
    }

    public static Vec3 bodyCenter(Entity entity, float partialTick) {
        return eye(entity, partialTick).add(0, entity.getBbHeight() * 0.5 - entity.getEyeHeight(), 0);
    }

    /** Where {@code subLevel} is drawn this frame on a client, else where it logically is; null for no sub-level. */
    @Nullable
    public static Pose3dc renderPose(@Nullable SubLevelAccess subLevel) {
        return subLevel instanceof ClientSubLevelAccess client ? client.renderPose() : logicalPose(subLevel);
    }

    @Nullable
    public static Pose3dc logicalPose(@Nullable SubLevelAccess subLevel) {
        return subLevel == null ? null : subLevel.logicalPose();
    }

    @Nullable
    public static Pose3dc renderPose(Level level, BlockPos pos) {
        return renderPose(containing(level, pos));
    }

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
