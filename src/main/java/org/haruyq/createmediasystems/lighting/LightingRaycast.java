package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.WorldEvents;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
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
    /**
     * How long the render thread's block shapes are kept at most, in nanoseconds. A changed block's section is
     * forgotten at once (see register); this only bounds what a missed change could leave behind.
     */
    private static final long RENDER_CACHE_NANOS = 10_000_000_000L;
    /** Sections the render thread's cache holds at most (16 KB of references each) before it starts again. */
    private static final int RENDER_CACHE_SECTIONS = 1024;
    private static final List<AABB> EMPTY = List.of();
    private static final List<AABB> FULL = Shapes.block().toAabbs();
    private static Cache renderCache;
    private static Scene renderScene;
    private final Level level;
    private final BlockPos ignored;
    private final List<Snapshot> subLevels;
    private final List<LightingScreens.Panel> screens;
    private final Cache cache;
    private final boolean cacheShapes;
    /** While visible traces to a point: the block whose own shape holds that point, which doesn't hide it. */
    private BlockPos buried;
    private Vec3 visibilityOrigin;
    private Object2ByteOpenHashMap<Vec3> visibility;

    private record Snapshot(SubLevelAccess owner, Pose3dc pose, Matrix3d normals, AABB bounds) {
    }

    private record Scene(Level level, long frame, List<Snapshot> subLevels, List<LightingScreens.Panel> screens) {
    }

    /**
     * This frame's traces on the render thread: sub-levels where they are drawn, with the block shapes and the scene
     * shared by every trace of the frame. Elsewhere (a server), where they logically are.
     */
    public static LightingRaycast rendered(Level level, BlockPos ignored) {
        return new LightingRaycast(level, ignored, true);
    }

    /** Traces against where sub-levels logically are, with a cache of their own. */
    public static LightingRaycast logical(Level level, BlockPos ignored) {
        return new LightingRaycast(level, ignored, false);
    }

    private LightingRaycast(Level level, BlockPos ignored, boolean render) {
        this.level = level;
        this.ignored = ignored;
        if (render && level.isClientSide) {
            if (renderCache == null || renderCache.level != level || renderCache.sections.size() > RENDER_CACHE_SECTIONS
                    || System.nanoTime() - renderCache.created > RENDER_CACHE_NANOS) {
                renderCache = new Cache(level);
            }
            cache = renderCache;
        } else {
            cache = new Cache(level);
        }
        cacheShapes = render;
        Scene scene = scene(level, render, render ? SableCompat::renderPose : SableCompat::logicalPose);
        subLevels = scene.subLevels;
        screens = scene.screens;
    }

    private static Scene scene(Level level, boolean render, Function<SubLevelAccess, Pose3dc> poseOf) {
        boolean shared = render && level.isClientSide && WorldEvents.isClientWorld(level);
        long frame = shared ? WorldEvents.frame() : -1;
        if (shared && renderScene != null && renderScene.level == level && renderScene.frame == frame) {
            return renderScene;
        }
        List<Snapshot> snapshots = new ArrayList<>();
        for (SubLevelAccess subLevel : SableCompat.subLevels(level)) {
            Pose3d pose = new Pose3d(poseOf.apply(subLevel));
            snapshots.add(new Snapshot(subLevel, pose, pose.bakeIntoMatrix(new Matrix4d()).normal(new Matrix3d()),
                    bounds(subLevel, pose)));
        }
        Scene scene = new Scene(level, frame, List.copyOf(snapshots), LightingScreens.snapshot(level, poseOf));
        if (shared) {
            renderScene = scene;
        }
        return scene;
    }

    /** A separate cache for a background scan outside the sub-levels' bounds. */
    private LightingRaycast(LightingRaycast from) {
        level = from.level;
        ignored = from.ignored;
        subLevels = from.subLevels;
        screens = from.screens;
        cache = new Cache(level);
        cacheShapes = true;
    }

    LightingRaycast detached() {
        return new LightingRaycast(this);
    }

    static void register() {
        WorldEvents.onSectionChanged(LightingRaycast::sectionChanged);
        WorldEvents.onChunkLoaded(LightingRaycast::chunkLoaded);
        WorldEvents.onLevelChanged(() -> {
            renderCache = null;
            renderScene = null;
        });
    }

    private static void sectionChanged(int x, int y, int z) {
        if (renderCache != null) {
            renderCache.sections.remove(SectionPos.asLong(x, y, z));
            renderCache.lastKey = Long.MIN_VALUE;
        }
    }

    private static void chunkLoaded(int x, int z) {
        if (renderCache == null) {
            return;
        }
        for (var it = renderCache.sections.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (SectionPos.x(key) == x && SectionPos.z(key) == z) {
                it.remove();
            }
        }
        renderCache.lastKey = Long.MIN_VALUE;
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
        Hit nativeHit = result(trace(fromWorld, end, pos -> !SableCompat.inPlotGrid(level, pos)), null);
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
                    pos -> SableCompat.containing(level, pos) == snapshot.owner), snapshot);
            if (candidate != null && (best == null || fromWorld.distanceToSqr(candidate.point()) < fromWorld.distanceToSqr(best.point()))) {
                best = candidate;
            }
        }
        return best == null ? miss(toWorld, fromWorld) : best;
    }

    /** Shared face corners use the same trace within this snapshot and from the same origin. */
    public boolean visible(Vec3 from, Vec3 to) {
        if (visibility == null) {
            visibility = new Object2ByteOpenHashMap<>();
        }
        if (!from.equals(visibilityOrigin)) {
            visibility.clear();
            visibilityOrigin = from;
        }
        byte known = visibility.getByte(to);
        if (known != 0) {
            return known > 0;
        }
        // Faces are lit as their blocks are drawn, but traced against their collision shapes: a seat's cushion can lie
        // inside its own box, and that box would keep it dark. A point inside a shape is a model drawn apart from it,
        // so that shape isn't what hides it; one step of a stair still shades the other.
        buried = buried(to);
        Hit hit;
        try {
            hit = clip(from, to);
        } finally {
            buried = null;
        }
        boolean seen = hit.block().getType() == HitResult.Type.MISS || hit.point().distanceToSqr(to) < 1e-8;
        visibility.put(to, (byte) (seen ? 1 : -1));
        return seen;
    }

    @Nullable
    private BlockPos buried(Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        ClipContext context = new ClipContext(point, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty());
        double x = point.x - pos.getX(), y = point.y - pos.getY(), z = point.z - pos.getZ();
        for (AABB box : cache.boxes(pos, context, cacheShapes)) {
            if (box.contains(x, y, z)) {
                return pos;
            }
        }
        return null;
    }

    public boolean hasSubLevels(AABB bounds) {
        for (Snapshot snapshot : subLevels) {
            if (snapshot.bounds.intersects(bounds)) {
                return true;
            }
        }
        return false;
    }

    private static AABB bounds(SubLevelAccess subLevel, Pose3dc pose) {
        var box = subLevel.boundingBox();
        Pose3d logical = new Pose3d(subLevel.logicalPose());
        return SableCompat.transform(new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
                world -> pose.transformPosition(logical.transformPositionInverse(world))).inflate(0.001);
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
        if (level.isDebug()) {
            return BlockGetter.traverseBlocks(from, to, context, (clip, pos) -> {
                if (pos.equals(ignored) || pos.equals(buried)) {
                    return null;
                }
                List<AABB> boxes = cache.boxes(pos, clip, cacheShapes);
                return boxes.isEmpty() || !allowed.test(pos) ? null : clip(boxes, clip.getFrom(), clip.getTo(), pos);
            }, clip -> null);
        }
        if (from.equals(to)) {
            return null;
        }
        double ax = Mth.lerp(-1e-7, from.x, to.x), ay = Mth.lerp(-1e-7, from.y, to.y), az = Mth.lerp(-1e-7, from.z, to.z);
        double dx = Mth.lerp(-1e-7, to.x, from.x) - ax;
        double dy = Mth.lerp(-1e-7, to.y, from.y) - ay;
        double dz = Mth.lerp(-1e-7, to.z, from.z) - az;
        int x = Mth.floor(ax), y = Mth.floor(ay), z = Mth.floor(az);
        int sx = Mth.sign(dx), sy = Mth.sign(dy), sz = Mth.sign(dz);
        double ix = sx == 0 ? Double.MAX_VALUE : sx / dx;
        double iy = sy == 0 ? Double.MAX_VALUE : sy / dy;
        double iz = sz == 0 ? Double.MAX_VALUE : sz / dz;
        double tx = ix * (sx > 0 ? 1 - Mth.frac(ax) : Mth.frac(ax));
        double ty = iy * (sy > 0 ? 1 - Mth.frac(ay) : Mth.frac(ay));
        double tz = iz * (sz > 0 ? 1 - Mth.frac(az) : Mth.frac(az));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (true) {
            pos.set(x, y, z);
            List<AABB> boxes = cache.boxes(pos, context, cacheShapes);
            Section section = cache.last;
            boolean empty = !section.missing && section.blocks == null;
            int sectionX = x >> 4, sectionY = y >> 4, sectionZ = z >> 4;
            while (true) {
                if (!empty && !boxes.isEmpty() && !pos.equals(ignored) && !pos.equals(buried) && allowed.test(pos)) {
                    BlockHitResult hit = clip(boxes, from, to, pos);
                    if (hit != null) {
                        return hit;
                    }
                }
                if (!(tx <= 1 || ty <= 1 || tz <= 1)) {
                    return null;
                }
                if (tx < ty && tx < tz) {
                    x += sx;
                    tx += ix;
                } else if (ty < tz) {
                    y += sy;
                    ty += iy;
                } else {
                    z += sz;
                    tz += iz;
                }
                if (x >> 4 != sectionX || y >> 4 != sectionY || z >> 4 != sectionZ) {
                    break;
                }
                // Keep vanilla's boundary order; look up the section once and skip its blocks if it's empty.
                if (!empty) {
                    pos.set(x, y, z);
                    boxes = section.boxes(level, pos, context, cacheShapes);
                }
            }
        }
    }

    /**
     * VoxelShape.clip with the shape's boxes kept: it lists them anew each call, most of a trace's time for a shape
     * of many boxes.
     */
    private static BlockHitResult clip(List<AABB> boxes, Vec3 from, Vec3 to, BlockPos pos) {
        Vec3 d = to.subtract(from);
        if (d.lengthSqr() < 1.0E-7) {
            return null;
        }
        // Starting inside the shape, it's hit where it starts.
        Vec3 start = from.add(d.scale(0.001));
        double x = start.x - pos.getX(), y = start.y - pos.getY(), z = start.z - pos.getZ();
        for (AABB box : boxes) {
            if (box.contains(x, y, z)) {
                return new BlockHitResult(start, Direction.getNearest(d.x, d.y, d.z).getOpposite(), pos, true);
            }
        }
        return AABB.clip(boxes, from, to, pos);
    }

    private static List<AABB> boxes(VoxelShape shape) {
        return shape.isEmpty() ? EMPTY : shape == Shapes.block() ? FULL : shape.toAabbs();
    }

    private static final class Cache {
        private final Level level;
        private final long created = System.nanoTime();
        private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        private long lastKey = Long.MIN_VALUE;
        private Section last;

        Cache(Level level) {
            this.level = level;
        }

        List<AABB> boxes(BlockPos pos, ClipContext context, boolean shapes) {
            if (level.isDebug()) {
                var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
                return chunk == null ? FULL : LightingRaycast.boxes(context.getBlockShape(chunk.getBlockState(pos), level, pos));
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
            return last.boxes(level, pos, context, shapes);
        }
    }

    private static final class Section {
        private final LevelChunkSection blocks;
        private final boolean missing;
        private List<AABB>[] shapes;

        Section(LevelChunkSection blocks, boolean missing) {
            this.blocks = blocks == null || blocks.hasOnlyAir() ? null : blocks;
            this.missing = missing;
        }

        @SuppressWarnings("unchecked")
        List<AABB> boxes(Level level, BlockPos pos, ClipContext context, boolean cache) {
            if (missing) {
                return FULL;
            }
            if (blocks == null) {
                return EMPTY;
            }
            int x = pos.getX() & 15, y = pos.getY() & 15, z = pos.getZ() & 15;
            int index = y << 8 | z << 4 | x;
            if (cache && shapes == null) {
                shapes = new List[4096];
            }
            List<AABB> boxes = shapes == null ? null : shapes[index];
            if (boxes == null) {
                var state = blocks.getBlockState(x, y, z);
                boxes = state.isAir() ? EMPTY : LightingRaycast.boxes(context.getBlockShape(state, level, pos));
                if (shapes != null) {
                    shapes[index] = boxes;
                }
            }
            return boxes;
        }
    }

    private static Hit miss(Vec3 to, Vec3 from) {
        Direction direction = Direction.getNearest(from.x - to.x, from.y - to.y, from.z - to.z);
        return new Hit(BlockHitResult.miss(to, direction, BlockPos.containing(to)), to, Vec3.ZERO);
    }

    public record Hit(BlockHitResult block, Vec3 point, Vec3 normal) {
    }
}
