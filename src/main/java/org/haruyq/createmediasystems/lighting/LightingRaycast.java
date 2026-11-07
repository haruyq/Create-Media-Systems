package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One frame's Sable-aware beam traces. Endpoints and the returned point/normal are world-space. */
public final class LightingRaycast {
    private static Cache renderCache;
    private final Level level;
    private final BlockPos ignored;
    private final List<Snapshot> subLevels;
    private final List<LightingScreens.Panel> screens;
    private final Cache cache;
    private boolean cacheShapes;

    private record Snapshot(SubLevelAccess owner, Pose3dc pose, Matrix3d normals, AABB bounds) {
    }

    public LightingRaycast(Level level, BlockPos ignored, boolean render) {
        this.level = level;
        this.ignored = ignored;
        if (render && level.isClientSide) {
            if (renderCache == null || renderCache.level != level) {
                renderCache = new Cache(level);
            }
            cache = renderCache;
        } else {
            cache = new Cache(level);
        }
        List<Snapshot> snapshots = new ArrayList<>();
        for (SubLevelAccess subLevel : SableCompanion.INSTANCE.getAllIntersecting(level,
                new BoundingBox3d(-30_000_000, -30_000_000, -30_000_000, 30_000_000, 30_000_000, 30_000_000))) {
            Pose3d pose = new Pose3d(render && subLevel instanceof ClientSubLevelAccess client
                    ? client.renderPose() : subLevel.logicalPose());
            snapshots.add(new Snapshot(subLevel, pose, pose.bakeIntoMatrix(new Matrix4d()).normal(new Matrix3d()),
                    bounds(subLevel, pose)));
        }
        this.subLevels = List.copyOf(snapshots);
        this.screens = LightingScreens.snapshot(level, render);
    }

    public Hit clip(Vec3 fromWorld, Vec3 toWorld) {
        Hit best = null;
        for (LightingScreens.Panel screen : screens) {
            Hit hit = screen.clip(fromWorld, toWorld);
            if (hit != null && !hit.block().getBlockPos().equals(ignored)
                    && (best == null || fromWorld.distanceToSqr(hit.point()) < fromWorld.distanceToSqr(best.point()))) {
                best = hit;
            }
        }
        Vec3 end = best == null ? toWorld : best.point();
        Hit nativeHit = result(trace(fromWorld, end, pos -> !SableCompanion.INSTANCE.isInPlotGrid(level, pos)), null);
        if (nativeHit != null && (best == null || fromWorld.distanceToSqr(nativeHit.point()) < fromWorld.distanceToSqr(best.point()))) {
            best = nativeHit;
        }
        for (Snapshot snapshot : subLevels) {
            Vec3 from = snapshot.bounds.contains(fromWorld) ? fromWorld : snapshot.bounds.clip(fromWorld, toWorld).orElse(null);
            if (from == null) {
                continue;
            }
            Vec3 to = snapshot.bounds.contains(toWorld) ? toWorld : snapshot.bounds.clip(toWorld, fromWorld).orElse(null);
            if (to == null) {
                continue;
            }
            Hit candidate = result(trace(snapshot.pose.transformPositionInverse(from), snapshot.pose.transformPositionInverse(to),
                    pos -> SableCompanion.INSTANCE.getContaining(level, pos) == snapshot.owner), snapshot);
            if (candidate != null && (best == null || fromWorld.distanceToSqr(candidate.point()) < fromWorld.distanceToSqr(best.point()))) {
                best = candidate;
            }
        }
        return best == null ? miss(toWorld, fromWorld) : best;
    }

    void cacheShapes() {
        cacheShapes = true;
    }

    static void beginFrame() {
        renderCache = null;
    }

    private static AABB bounds(SubLevelAccess subLevel, Pose3dc pose) {
        var box = subLevel.boundingBox();
        Pose3d logical = new Pose3d(subLevel.logicalPose());
        AABB result = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 world = new Vec3((corner & 1) == 0 ? box.minX() : box.maxX(),
                    (corner & 2) == 0 ? box.minY() : box.maxY(), (corner & 4) == 0 ? box.minZ() : box.maxZ());
            Vec3 at = pose.transformPosition(logical.transformPositionInverse(world));
            result = result == null ? new AABB(at, at) : result.minmax(new AABB(at, at));
        }
        return result.inflate(0.001);
    }

    private Hit result(BlockHitResult hit, Snapshot snapshot) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(ignored)) {
            return null;
        }
        Vec3 point = hit.getLocation();
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        if (snapshot != null) {
            point = snapshot.pose.transformPosition(point);
            Vector3d transformed = snapshot.normals.transform(new Vector3d(normal.x, normal.y, normal.z)).normalize();
            normal = new Vec3(transformed.x, transformed.y, transformed.z);
        }
        return new Hit(hit, point, normal);
    }

    private BlockHitResult trace(Vec3 from, Vec3 to, Predicate<BlockPos> allowed) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty());
        return BlockGetter.traverseBlocks(from, to, context, (clip, pos) -> {
            if (pos.equals(ignored) || !allowed.test(pos)) {
                return null;
            }
            VoxelShape shape = cache.shape(pos, clip, cacheShapes);
            return shape == Shapes.empty() ? null : shape.clip(clip.getFrom(), clip.getTo(), pos);
        }, clip -> null);
    }

    private static final class Cache {
        private final Level level;
        private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        private long lastKey = Long.MIN_VALUE;
        private Section last;

        Cache(Level level) {
            this.level = level;
        }

        VoxelShape shape(BlockPos pos, ClipContext context, boolean shapes) {
            if (level.isDebug()) {
                var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
                return chunk == null ? Shapes.block() : context.getBlockShape(chunk.getBlockState(pos), level, pos);
            }
            long key = SectionPos.asLong(pos);
            if (key != lastKey) {
                lastKey = key;
                last = sections.get(key);
                if (last == null) {
                    var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
                    int index = level.getSectionIndex(pos.getY());
                    last = new Section(chunk == null || index < 0 || index >= level.getSectionsCount()
                            ? null : chunk.getSection(index), chunk == null);
                    sections.put(key, last);
                }
            }
            return last.shape(level, pos, context, shapes);
        }
    }

    private static final class Section {
        private final LevelChunkSection blocks;
        private final boolean missing;
        private VoxelShape[] shapes;

        Section(LevelChunkSection blocks, boolean missing) {
            this.blocks = blocks == null || blocks.hasOnlyAir() ? null : blocks;
            this.missing = missing;
        }

        VoxelShape shape(Level level, BlockPos pos, ClipContext context, boolean cache) {
            if (missing) {
                return Shapes.block();
            }
            if (blocks == null) {
                return Shapes.empty();
            }
            int x = pos.getX() & 15, y = pos.getY() & 15, z = pos.getZ() & 15;
            int index = y << 8 | z << 4 | x;
            if (cache && shapes == null) {
                shapes = new VoxelShape[4096];
            }
            VoxelShape shape = shapes == null ? null : shapes[index];
            if (shape == null) {
                var state = blocks.getBlockState(x, y, z);
                shape = state.isAir() ? Shapes.empty() : context.getBlockShape(state, level, pos);
                if (shape.isEmpty()) {
                    shape = Shapes.empty();
                }
                if (shapes != null) {
                    shapes[index] = shape;
                }
            }
            return shape;
        }
    }

    private static Hit miss(Vec3 to, Vec3 from) {
        Direction direction = Direction.getNearest(from.x - to.x, from.y - to.y, from.z - to.z);
        return new Hit(BlockHitResult.miss(to, direction, BlockPos.containing(to)), to, Vec3.ZERO);
    }

    public record Hit(BlockHitResult block, Vec3 point, Vec3 normal) {
    }
}
