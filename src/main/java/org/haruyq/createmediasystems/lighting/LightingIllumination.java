package org.haruyq.createmediasystems.lighting;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.Workers;
import org.haruyq.createmediasystems.display.ScreenLight;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * A stage light's light on the block faces its beam reaches. Faces are found in the background, or in bounded
 * steps with Sable. Their meshes and occlusion are kept until blocks or the light move; the GPU applies the
 * current cone (shaders/core/spot_light). Entities receive light through their light coordinates (light).
 */
public final class LightingIllumination {
    private static final Map<StageLightBlock.Entity, Beam> BEAMS = new HashMap<>();
    private static final ArrayDeque<SurfaceScan> SCANS = new ArrayDeque<>();
    private static WeakReference<Level> knownLevel = new WeakReference<>(null);
    /** POSITION_TEX_COLOR_NORMAL's layout, its own instance: Iris extends some vanilla formats by identity. */
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    private static ShaderInstance shader;
    /** This frame's lit surfaces in the world, drawn with the beams (LightingRenderer.effects). */
    private static final List<Runnable> SURFACES = new ArrayList<>();
    /** Sections and chunk columns whose blocks changed since the last frame (LevelRendererMixin). */
    private static final LongOpenHashSet DIRTY_SECTIONS = new LongOpenHashSet();
    private static final LongOpenHashSet DIRTY_COLUMNS = new LongOpenHashSet();
    /** Vertices built on the render thread, in native memory, till uploaded. */
    private static final ByteBufferBuilder SCRATCH = new ByteBufferBuilder(FORMAT.getVertexSize() * 4096);
    private static long frame;

    private LightingIllumination() {
    }

    /**
     * Blocks past what a moving beam needs that its faces are found for, so it rescans only every few blocks; the
     * next scan starts in the background once it has crossed half of them, to be ready before it runs out.
     */
    private static final int SCAN_MARGIN = 6;
    private static final int SCAN_BUDGET = 1024;
    /** Rays a beam may trace per frame for faces in it, including newly found faces. */
    private static final int REFRESH_BUDGET = 64;
    /** Rays a beam may trace per frame for faces outside it, so they're known by the time it reaches them. */
    private static final int AHEAD_BUDGET = 32;
    private static final int SCAN_RAYS = 2048;
    /** How far, in blocks, the light may move before the faces are traced from where it is again. */
    private static final double SIGHT_TOLERANCE = 1 / 8.0;
    /** Blocks past the beam's edge whose faces are traced at once, so the beam finds them lit as it moves. */
    private static final double LEAD = 2;
    /** Frames a lamp may go undrawn before its buffer is freed. */
    private static final int FORGET_FRAMES = 600;

    static final class TraceBudget {
        int beam;
        int ahead;

        void reset() {
            beam = REFRESH_BUDGET;
            ahead = AHEAD_BUDGET;
        }

        boolean take(int count, boolean inBeam) {
            if (count > (inBeam ? beam : ahead)) {
                return false;
            }
            if (inBeam) {
                beam -= count;
            } else {
                ahead -= count;
            }
            return true;
        }
    }

    private static final class Beam {
        Vec3 origin;
        Vec3 direction;
        double tangent;
        float range;
        float strength;
        long at = -1;
        /** The faces found may be out of date: blocks changed near them, or it went undrawn a while. */
        boolean stale = true;
        /** A scan under way, adopted once done (see adopt). */
        CompletableFuture<Scan> scanning;
        /** The tick the lit faces were last checked to still stand; faces gone are set to null (see render). */
        long checked = Long.MIN_VALUE;
        /**
         * What the light sees from: bumped when it moves SIGHT_TOLERANCE from sightFrom, a block changes near the
         * beam, or it went undrawn a while. Faces traced under another sight are traced again (refresh).
         */
        long sight = 1;
        Vec3 sightFrom;
        /** The sight and direction under which every face in the beam was last found traced, so no work is left. */
        long settled;
        Vec3 settledDirection;
        /** The last scan's centre, radius across the surface hit, and that surface's axis. */
        BlockPos center;
        int radius;
        Direction.Axis axis;
        List<Face> faces = new ArrayList<>();
        /** Each face's middle and unit normal in the lamp's block space (6 doubles a face), as still: see centers. */
        double[] centers = new double[0];
        /** Faces with a point not yet traced. */
        int untraced;
        int next;
        // This frame's, see render.
        LightingRaycast rays;
        Vec3 from;
        boolean moving;
        final TraceBudget budget = new TraceBudget();
        int face;
        int resume;
        /** The buffer no longer shows what the light sees: a cell in the beam changed (dirty), or one outside it (pending). */
        boolean dirty;
        boolean pending;
        boolean geometryDirty = true;
        boolean resident;
        final BitSet changedFaces = new BitSet();
        int[] slots = new int[0];
        byte[] cells = new byte[0];
        int litCells;
        DynamicTexture visibility;
        long built = Long.MIN_VALUE;
        long drawn;
        VertexBuffer buffer;
        boolean empty = true;
        // As drawn this frame.
        final Vector3f drawOrigin = new Vector3f();
        final Vector3f drawDirection = new Vector3f();
        final Vector3f drawColor = new Vector3f();
        float drawTangent;
        float drawRange;
        float drawStrength;
        final RenderType type = RenderType.create("create_media_systems_spot_light", FORMAT, VertexFormat.Mode.QUADS,
                256, false, false, RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> shader))
                        .setTextureState(new RenderStateShard.EmptyTextureStateShard(this::setup, () -> {
                        }))
                        .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                        .createCompositeState(false));

        private void setup() {
            Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).setFilter(false, true);
            RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
            RenderSystem.setShaderTexture(1, resident && visibility != null ? visibility.getId() : 0);
            shader.safeGetUniform("Masked").set(resident ? 1 : 0);
            shader.safeGetUniform("Origin").set(drawOrigin);
            shader.safeGetUniform("Direction").set(drawDirection);
            shader.safeGetUniform("Tangent").set(drawTangent);
            shader.safeGetUniform("Range").set(drawRange);
            shader.safeGetUniform("Strength").set(drawStrength);
            shader.safeGetUniform("LightColor").set(drawColor);
        }

        /**
         * Traces again what the light saw of {@code face} under another sight: a whole face at a time, so none is
         * half from one place and half from another, within REFRESH_BUDGET a frame, starting where the last frame's
         * budget ran out (next).
         */
        void refresh(Face face, Geometry geometry) {
            if (face.sight == 0 || face.sight == sight && !moving) {
                return;
            }
            if (!budget.take(face.seen.length, true)) {
                if (resume < 0) {
                    resume = this.face;
                }
                return;
            }
            for (int i = 0; i < face.seen.length; i++) {
                if (face.seen[i] == 0) {
                    continue;
                }
                byte seen = (byte) (rays.visible(from, geometry.rays[i]) ? 1 : -1);
                if (seen != face.seen[i]) {
                    face.seen[i] = seen;
                    dirty = true;
                    changedFaces.set(this.face);
                }
            }
            face.sight = sight;
        }

        /** Traces unknown points within the beam's remaining budget. */
        void ensure(Face face, Geometry geometry, boolean inBeam) {
            if (!face.untraced) {
                return;
            }
            for (int i = 0; i < face.seen.length; i++) {
                if (face.seen[i] != 0) {
                    continue;
                }
                if (!budget.take(1, inBeam)) {
                    if (resume < 0) {
                        resume = this.face;
                    }
                    return;
                }
                if (face.sight == 0) {
                    face.sight = sight;
                }
                face.seen[i] = (byte) (rays.visible(from, geometry.rays[i]) ? 1 : -1);
                changedFaces.set(this.face);
                if (inBeam) {
                    dirty = true;
                } else {
                    pending = true;
                }
            }
            face.untraced = false;
            untraced--;
        }

        void adopt(Scan scan) {
            faces = scan.faces;
            centers = scan.centers;
            untraced = 0;
            for (Face face : faces) {
                if (face.untraced) {
                    untraced++;
                }
            }
            center = scan.center;
            radius = scan.radius;
            axis = scan.axis;
            next = 0;
            dirty = true;
            geometryDirty = true;
            changedFaces.clear();
        }

        /** Whether the last scan holds every face {@code radius} around {@code center} on a surface on {@code axis}. */
        boolean covers(BlockPos center, int radius, Direction.Axis axis) {
            if (this.center == null || axis != this.axis) {
                return false;
            }
            int dx = Math.abs(center.getX() - this.center.getX());
            int dy = Math.abs(center.getY() - this.center.getY());
            int dz = Math.abs(center.getZ() - this.center.getZ());
            // Across the surface, the scan's radius; along its axis, the scan reaches 2 blocks either way.
            return (axis == Direction.Axis.X ? dx <= 1 : dx + radius <= this.radius)
                    && (axis == Direction.Axis.Y ? dy <= 1 : dy + radius <= this.radius)
                    && (axis == Direction.Axis.Z ? dz <= 1 : dz + radius <= this.radius);
        }

        /** Whether a block changed this frame between the lamp at {@code lamp} and the faces found. */
        boolean touched(BlockPos lamp) {
            if (center == null || DIRTY_SECTIONS.isEmpty() && DIRTY_COLUMNS.isEmpty()) {
                return false;
            }
            int reach = radius + 2;
            int x0 = Math.min(lamp.getX(), center.getX() - reach) >> 4, x1 = Math.max(lamp.getX(), center.getX() + reach) >> 4;
            int y0 = Math.min(lamp.getY(), center.getY() - reach) >> 4, y1 = Math.max(lamp.getY(), center.getY() + reach) >> 4;
            int z0 = Math.min(lamp.getZ(), center.getZ() - reach) >> 4, z1 = Math.max(lamp.getZ(), center.getZ() + reach) >> 4;
            for (long column : DIRTY_COLUMNS) {
                int x = ChunkPos.getX(column), z = ChunkPos.getZ(column);
                if (x >= x0 && x <= x1 && z >= z0 && z <= z1) {
                    return true;
                }
            }
            for (long section : DIRTY_SECTIONS) {
                int x = SectionPos.x(section), y = SectionPos.y(section), z = SectionPos.z(section);
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) {
                    return true;
                }
            }
            return false;
        }

        /** Draws the lit faces, their vertices in the lamp's block space, with the lamp at {@code modelView}. */
        void draw(Matrix4f modelView, Matrix4f projection) {
            if (buffer == null || empty || shader == null) {
                return;
            }
            type.setupRenderState();
            buffer.bind();
            buffer.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            type.clearRenderState();
        }

        void close() {
            if (scanning != null) {
                scanning.cancel(false);
            }
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            if (visibility != null) {
                visibility.close();
                visibility = null;
            }
        }
    }

    /** A block model's quad near the beam, {@code quad}th of its block's, and what the light last saw of it. */
    private static final class Face {
        final BlockPos pos;
        final BlockState state;
        final int quad;
        /** The model quad's own vertex data, to know it again in the next scan. */
        final int[] vertices;
        /** Its corners in the world's block coordinates (a Sable sub-level's own, if on one). */
        final Vec3[] points;
        final float[] uv;
        final double winding;
        final int tint;
        /** As the lamp sees it with nothing moving (see geometry). */
        final Geometry still;
        /** Packed FORMAT vertices, reused when the next scan keeps this face. */
        final byte[] mesh;
        /** As this frame saw it, with sub-levels where they are: still, unless the lamp and face are on different ones. */
        Geometry shown;
        /** Each point's sight (see Geometry): 0 not yet, 1 lit, -1 shaded; and the sight (Beam.sight) it was traced under. */
        final byte[] seen = new byte[13];
        boolean untraced = true;
        long sight;

        Face(BlockPos pos, BlockState state, int quad, int[] vertices, Vec3[] points, float[] uv, double winding, int tint,
             Vec3 offset) {
            this.pos = pos;
            this.state = state;
            this.quad = quad;
            this.vertices = vertices;
            this.points = points;
            this.uv = uv;
            this.winding = winding;
            this.tint = tint;
            this.still = geometry(null, null, offset);
            this.mesh = vertices(still.samples, still.normal, uv, tint);
            this.shown = still;
        }

        /** With the face's and the lamp's sub-levels where they are now, and the lamp at {@code offset}. */
        Geometry geometry(Pose3dc facePose, Pose3dc lampPose, Vec3 offset) {
            Vec3[] world = new Vec3[4];
            Vec3[] local = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                world[i] = facePose == null ? points[i] : facePose.transformPosition(points[i]);
                local[i] = (lampPose == null ? world[i] : lampPose.transformPositionInverse(world[i])).subtract(offset);
            }
            return Geometry.of(local, world, winding);
        }

        /** Whether the light reaches the whole of cell ({@code x}, {@code y}) of the 2×2 over the face. */
        boolean lit(int x, int y) {
            return cellLit(seen, x, y);
        }
    }

    static boolean cellLit(byte[] seen, int x, int y) {
        int corner = y * 3 + x;
        return seen[9 + y * 2 + x] > 0 && seen[corner] > 0 && seen[corner + 1] > 0 && seen[corner + 3] > 0
                && seen[corner + 4] > 0;
    }

    static byte[] vertices(Vec3[] samples, Vec3 normal, float[] uv, int tint) {
        ByteBuffer out = ByteBuffer.allocate(FORMAT.getVertexSize() * 16).order(ByteOrder.nativeOrder());
        byte nx = (byte) (Mth.clamp((float) normal.x, -1, 1) * 127);
        byte ny = (byte) (Mth.clamp((float) normal.y, -1, 1) * 127);
        byte nz = (byte) (Mth.clamp((float) normal.z, -1, 1) * 127);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                for (int corner = 0; corner < 4; corner++) {
                    int cx = x + (corner == 1 || corner == 2 ? 1 : 0);
                    int cy = y + (corner >= 2 ? 1 : 0);
                    float u = cx * 0.5f;
                    float v = cy * 0.5f;
                    Vec3 point = samples[cy * 3 + cx];
                    out.putFloat((float) point.x).putFloat((float) point.y).putFloat((float) point.z);
                    out.putFloat(Mth.lerp(v, Mth.lerp(u, uv[0], uv[2]), Mth.lerp(u, uv[6], uv[4])));
                    out.putFloat(Mth.lerp(v, Mth.lerp(u, uv[1], uv[3]), Mth.lerp(u, uv[7], uv[5])));
                    out.put((byte) (tint >> 16)).put((byte) (tint >> 8)).put((byte) tint).put((byte) 255);
                    out.put(nx).put(ny).put(nz).put((byte) 0);
                }
            }
        }
        return out.array();
    }

    /**
     * A face as the lamp sees it: in the lamp's block space, the corners of a 3×3 grid over it (samples, 0..8, row
     * by row), its unit normal towards the light's side and its middle; and in the world, the same 9 points and the
     * 2×2 cells' centres (9..12) lifted just off it, to trace the light to (rays).
     */
    private record Geometry(Vec3[] samples, Vec3 normal, Vec3 middle, Vec3[] rays) {
        static Geometry of(Vec3[] local, Vec3[] world, double winding) {
            Vec3 normal = local[1].subtract(local[0]).cross(local[3].subtract(local[0])).normalize().scale(winding);
            Vec3 lift = world[1].subtract(world[0]).cross(world[3].subtract(world[0])).normalize().scale(winding * 0.001);
            Vec3[] samples = new Vec3[9];
            Vec3[] rays = new Vec3[13];
            for (int i = 0; i < 9; i++) {
                float u = i % 3 * 0.5f;
                float v = i / 3 * 0.5f;
                samples[i] = sample(local, u, v, Vec3.ZERO);
                rays[i] = sample(world, u, v, lift);
            }
            for (int i = 0; i < 4; i++) {
                rays[9 + i] = sample(world, (i % 2 + 0.5f) / 2, (i / 2 + 0.5f) / 2, lift);
            }
            return new Geometry(samples, normal, local[0].lerp(local[2], 0.5), rays);
        }
    }

    static float exposure(Vec3 offset, Vec3 direction, double tangent, double range, double margin) {
        return exposure(offset.x, offset.y, offset.z, direction, tangent, range, margin);
    }

    /** How brightly the light reaches a point {@code (x, y, z)} from it, 0 to 1 (spot_light.fsh does the same). */
    static float exposure(double x, double y, double z, Vec3 direction, double tangent, double range, double margin) {
        double along = x * direction.x + y * direction.y + z * direction.z;
        if (along <= 0 || along >= range) {
            return 0;
        }
        double radius = Math.max(0.08, along * tangent);
        double radial = Math.max(0, Math.sqrt(Math.max(0, x * x + y * y + z * z - along * along)) - margin) / radius;
        float edge = Mth.clamp((float) ((1 - radial) / 0.35), 0, 1);
        float distance = Mth.clamp((float) ((range - along) / Math.max(1, range * 0.2)), 0, 1);
        return edge * edge * (3 - 2 * edge) * distance;
    }

    static int packedLight(int original, float intensity) {
        int block = Math.round(15 * (float) Math.sqrt(Mth.clamp(intensity * 4, 0, 1)));
        return LightTexture.pack(Math.max(LightTexture.block(original), block), LightTexture.sky(original));
    }

    public static int light(Entity entity, float partialTick, int original) {
        if (knownLevel.get() != entity.level() || LightTexture.block(original) == 15 || LightingRenderer.shadowPass()) {
            return original;
        }
        Vec3 target = SableCompanion.INSTANCE.getEyePositionInterpolated(entity, partialTick)
                .add(0, entity.getBbHeight() * 0.5 - entity.getEyeHeight(), 0);
        float intensity = 0;
        long now = entity.level().getGameTime();
        for (var entry : BEAMS.entrySet()) {
            StageLightBlock.Entity lamp = entry.getKey();
            Beam beam = entry.getValue();
            if (lamp.isRemoved() || beam.origin == null || now < beam.at || now - beam.at > 2) {
                continue;
            }
            // A fixture lit without a console (another mod's, on redstone, say) is lit while it draws its beam.
            var console = lamp.controller();
            if (console == null ? lamp.console != null : !console.formed || !console.settings.enabled
                    || console.settings.master <= 0 || console.settings.groups[lamp.group].brightness <= 0) {
                continue;
            }
            Vec3 local = SableCompat.toRenderBlockSpace(entity.level(), lamp.getBlockPos(), target)
                    .subtract(Vec3.atLowerCornerOf(lamp.getBlockPos()));
            float value = exposure(local.subtract(beam.origin), beam.direction, beam.tangent, beam.range,
                    entity.getBbWidth() * 0.35) * beam.strength;
            Vec3 origin = SableCompat.toRenderWorld(entity.level(), lamp.getBlockPos(),
                    beam.origin.add(Vec3.atLowerCornerOf(lamp.getBlockPos())));
            if (value > 0 && visible(new LightingRaycast(entity.level(), lamp.getBlockPos(), true), origin, target)) {
                intensity += value;
            }
        }
        return packedLight(original, intensity);
    }

    /** The most blocks across the surface hit a stage light's faces are found for, either way from the hit. */
    public static final int STAGE_RADIUS = 8;
    /** The most a fixture may ask for (see render): a floodlight over a hall's floor. */
    public static final int MAX_RADIUS = 48;

    public static void render(StageLightBlock.Entity lamp, Vec3 origin, Vec3 direction, double halfAngle, float range,
                              float strength, int color, LightingRaycast.Hit hit, LightingRaycast rays,
                              Matrix4f matrix, MultiBufferSource buffers) {
        render(lamp, origin, direction, halfAngle, range, strength, color, hit, rays, matrix, buffers, STAGE_RADIUS);
    }

    /**
     * The light of {@code lamp}'s beam on the faces it reaches this frame, {@code origin} and {@code direction} in
     * its block space. {@code hit} is where the beam's middle meets a block (from {@code rays}); faces are found up
     * to {@code maxRadius} blocks across that surface from it (at most MAX_RADIUS), so a wide beam from far off
     * lights all of the floor it falls on.
     */
    public static void render(StageLightBlock.Entity lamp, Vec3 origin, Vec3 direction, double halfAngle, float range,
                              float strength, int color, LightingRaycast.Hit hit, LightingRaycast rays,
                              Matrix4f matrix, MultiBufferSource buffers, int maxRadius) {
        Level level = lamp.getLevel();
        if (knownLevel.get() != level) {
            forgetAll();
            knownLevel = new WeakReference<>(level);
        }
        Beam beam = BEAMS.computeIfAbsent(lamp, ignored -> new Beam());
        long now = level.getGameTime();
        if (frame - beam.drawn > 1 || now < beam.at) {
            // Undrawn a while (off screen, say): blocks may have changed unseen (see sectionChanged).
            beam.sight++;
            beam.stale = true;
        }
        beam.origin = origin;
        beam.direction = direction;
        beam.tangent = Math.tan(halfAngle);
        beam.range = range;
        beam.strength = strength * Math.max(color >> 16 & 255, Math.max(color >> 8 & 255, color & 255)) / 255;
        beam.at = now;
        beam.drawn = frame;
        if (hit.block().getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos center = hit.block().getBlockPos();
        Vec3 offset = Vec3.atLowerCornerOf(lamp.getBlockPos());
        Pose3dc lampPose = renderPose(level, lamp.getBlockPos());
        Vec3 impact = (lampPose == null ? hit.point() : lampPose.transformPositionInverse(hit.point())).subtract(offset);
        int radius = Mth.ceil(Math.min(Mth.clamp(maxRadius, 1, MAX_RADIUS), origin.distanceTo(impact) * beam.tangent)) + 1;
        Direction.Axis axis = hit.block().getDirection().getAxis();
        if (beam.touched(lamp.getBlockPos())) {
            beam.sight++;
            beam.stale = true;
        }
        Vec3 absoluteOrigin = origin.add(offset);
        Vec3 from = lampPose == null ? absoluteOrigin : lampPose.transformPosition(absoluteOrigin);
        Pose3dc targetPose = renderPose(level, center);
        boolean moving = lampPose != null || targetPose != null
                || rays.hasSubLevels(new AABB(from, hit.point()).inflate((radius + SCAN_MARGIN + 2) * Math.sqrt(3)));
        if (moving != beam.moving) {
            beam.sight++;
        }
        beam.moving = moving;
        if (beam.sightFrom == null || from.distanceToSqr(beam.sightFrom) >= SIGHT_TOLERANCE * SIGHT_TOLERANCE) {
            beam.sight++;
            beam.sightFrom = from;
        }
        if (beam.scanning != null && beam.scanning.isDone()) {
            Scan found = beam.scanning.getNow(null);
            beam.scanning = null;
            if (found != null) {
                beam.adopt(found);
            } else {
                beam.stale = true;
            }
        }
        boolean moved = !center.equals(beam.center);
        if (beam.scanning == null && (beam.stale || !beam.covers(center, radius, axis)
                || moved && !beam.covers(center, radius + SCAN_MARGIN / 2, axis))) {
            // Moving across the surface, found a little further out: the next few blocks need no scan of their own.
            int scan = radius + (moved ? SCAN_MARGIN : 0);
            beam.stale = false;
            List<Face> previous = new ArrayList<>(beam.faces);
            if (moving) {
                SurfaceScan job = new SurfaceScan(level, lamp.getBlockPos(), center, scan, axis, previous, true);
                beam.scanning = job.result;
                SCANS.addLast(job);
            } else {
                // Off this thread, the faces new to it traced there too: the light shows the old ones till it's done.
                LightingRaycast traced = rays.detached();
                BlockPos at = lamp.getBlockPos();
                long sight = beam.sight;
                double tangent = beam.tangent;
                // Rays in proportion to the surface: a floodlight's floor is traced there, not a few faces a frame.
                int budget = SCAN_RAYS * Math.max(1, scan * scan / ((STAGE_RADIUS + 1) * (STAGE_RADIUS + 1)));
                beam.scanning = CompletableFuture.supplyAsync(() -> scan(level, at, center, scan, axis, previous,
                        traced, origin, from, direction, tangent, range, sight, budget), Workers.LIGHT)
                        .exceptionally(LightingIllumination::failed);
            }
        }
        // Once a tick, the faces in the beam are checked to still stand; the rest wait till it reaches them.
        boolean check = beam.at != beam.checked;
        // On different sub-levels (or one on none), the faces move in the lamp's space: each frame sees them anew.
        BlockPos surface = beam.center == null ? center : beam.center;
        boolean posed = SableCompanion.INSTANCE.getContaining(level, lamp.getBlockPos())
                != SableCompanion.INSTANCE.getContaining(level, surface);
        // Every face found belongs to the scanned centre's sub-level, or to the world.
        Pose3dc facePose = renderPose(level, surface);
        // On the same moving sub-level, only where they are in the world (to trace to) changes.
        boolean carried = posed || facePose != null;
        // A still light with every face in its beam traced has nothing to look at till something changes.
        if (check || carried || beam.moving || beam.untraced > 0 || beam.settled != beam.sight
                || !direction.equals(beam.settledDirection)) {
            beam.rays = rays;
            beam.from = from;
            beam.budget.reset();
            beam.resume = -1;
            int count = beam.faces.size();
            int start = beam.next < count ? beam.next : 0;
            long checkedColumn = Long.MIN_VALUE;
            ChunkAccess checkedChunk = null;
            for (int n = 0; n < count; n++) {
                int i = (start + n) % count;
                beam.face = i;
                // Most faces found are outside the beam: they're turned away without a look at the face itself.
                double mx, my, mz, nx, ny, nz;
                Face face = null;
                Geometry geometry = null;
                if (carried) {
                    face = beam.faces.get(i);
                    if (face == null) {
                        continue;
                    }
                    geometry = face.geometry(facePose, lampPose, offset);
                    face.shown = posed ? geometry : face.still;
                    mx = geometry.middle.x;
                    my = geometry.middle.y;
                    mz = geometry.middle.z;
                    nx = geometry.normal.x;
                    ny = geometry.normal.y;
                    nz = geometry.normal.z;
                } else {
                    mx = beam.centers[i * 6];
                    my = beam.centers[i * 6 + 1];
                    mz = beam.centers[i * 6 + 2];
                    nx = beam.centers[i * 6 + 3];
                    ny = beam.centers[i * 6 + 4];
                    nz = beam.centers[i * 6 + 5];
                }
                double ix = origin.x - mx, iy = origin.y - my, iz = origin.z - mz;
                boolean inBeam = exposure(-ix, -iy, -iz, direction, beam.tangent, range, LEAD) > 0
                        && nx * ix + ny * iy + nz * iz > 0;
                if (!inBeam && (beam.untraced == 0 || beam.budget.ahead <= 0)) {
                    continue;
                }
                if (face == null) {
                    face = beam.faces.get(i);
                    if (face == null) {
                        continue;
                    }
                    geometry = face.still;
                }
                if (inBeam) {
                    if (check) {
                        long column = ChunkPos.asLong(face.pos);
                        if (column != checkedColumn) {
                            checkedColumn = column;
                            checkedChunk = level.getChunkSource().getChunk(face.pos.getX() >> 4, face.pos.getZ() >> 4,
                                    ChunkStatus.FULL, false);
                        }
                        if (checkedChunk == null || checkedChunk.getBlockState(face.pos) != face.state) {
                            beam.faces.set(i, null);
                            if (face.untraced) {
                                beam.untraced--;
                            }
                            beam.dirty = true;
                            beam.changedFaces.set(i);
                            continue;
                        }
                    }
                    beam.refresh(face, geometry);
                }
                beam.ensure(face, geometry, inBeam);
            }
            if (check) {
                beam.checked = beam.at;
            }
            if (beam.resume < 0) {
                beam.settled = beam.sight;
                beam.settledDirection = direction;
            }
            beam.next = beam.resume < 0 ? 0 : beam.resume;
            beam.rays = null;
        }
        if (beam.resident == posed) {
            beam.resident = !posed;
            beam.geometryDirty = true;
        }
        // Different moving sub-levels still rebuild their geometry; otherwise only changed visibility is uploaded.
        if (beam.geometryDirty || posed || (beam.dirty || beam.pending) && beam.built != beam.at) {
            upload(beam);
        }
        if (beam.empty) {
            return;
        }
        beam.drawOrigin.set((float) origin.x, (float) origin.y, (float) origin.z);
        beam.drawDirection.set((float) direction.x, (float) direction.y, (float) direction.z);
        beam.drawColor.set((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f);
        beam.drawTangent = (float) beam.tangent;
        beam.drawRange = range;
        beam.drawStrength = strength;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        if (level == Minecraft.getInstance().level) {
            Runnable draw = () -> beam.draw(modelView, projection);
            if (!ScreenLight.afterPack(draw)) {
                SURFACES.add(draw);
            }
        } else {
            beam.draw(modelView, projection);
        }
    }

    /** The lit cells of the beam's faces into its vertex buffer, in the lamp's block space. */
    private static void upload(Beam beam) {
        if (beam.resident && !beam.geometryDirty) {
            visibility(beam);
            beam.dirty = false;
            beam.pending = false;
            beam.built = beam.at;
            return;
        }
        MeshData mesh;
        int cells = 0;
        if (beam.resident) {
            beam.slots = new int[beam.faces.size()];
            Arrays.fill(beam.slots, -1);
            for (int i = 0; i < beam.faces.size(); i++) {
                if (beam.faces.get(i) != null) {
                    beam.slots[i] = cells;
                    cells += 4;
                }
            }
            mesh = null;
            if (cells > 0) {
                int count = cells * 4;
                int bytes = count * FORMAT.getVertexSize();
                ByteBuffer out = MemoryUtil.memByteBuffer(SCRATCH.reserve(bytes), bytes);
                for (Face face : beam.faces) {
                    if (face != null) {
                        out.put(face.mesh);
                    }
                }
                mesh = new MeshData(SCRATCH.build(), new MeshData.DrawState(FORMAT, count, cells * 6,
                        VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(count)));
            }
        } else {
            mesh = movingMesh(beam);
        }
        beam.empty = mesh == null;
        if (mesh != null) {
            if (beam.buffer == null) {
                beam.buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            beam.buffer.bind();
            beam.buffer.upload(mesh);
            VertexBuffer.unbind();
        }
        if (beam.resident) {
            int height = Math.max(1, (cells + 255) / 256);
            if (beam.visibility == null || beam.visibility.getPixels().getHeight() != height) {
                if (beam.visibility != null) {
                    beam.visibility.close();
                }
                beam.visibility = new DynamicTexture(256, height, false);
            }
            beam.cells = new byte[cells];
            Arrays.fill(beam.cells, (byte) -1);
            beam.litCells = 0;
            beam.changedFaces.set(0, beam.faces.size());
            visibility(beam);
        }
        beam.geometryDirty = false;
        beam.changedFaces.clear();
        beam.dirty = false;
        beam.pending = false;
        beam.built = beam.at;
    }

    private static MeshData movingMesh(Beam beam) {
        BufferBuilder out = new BufferBuilder(SCRATCH, VertexFormat.Mode.QUADS, FORMAT);
        for (Face face : beam.faces) {
            if (face == null) {
                continue;
            }
            Geometry geometry = face.shown;
            int r = face.tint >> 16 & 255, g = face.tint >> 8 & 255, b = face.tint & 255;
            float nx = (float) geometry.normal.x, ny = (float) geometry.normal.y, nz = (float) geometry.normal.z;
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    if (!face.lit(x, y)) {
                        continue;
                    }
                    for (int corner = 0; corner < 4; corner++) {
                        int cx = x + (corner == 1 || corner == 2 ? 1 : 0);
                        int cy = y + (corner >= 2 ? 1 : 0);
                        float u = cx * 0.5f;
                        float v = cy * 0.5f;
                        Vec3 point = geometry.samples[cy * 3 + cx];
                        float s = Mth.lerp(v, Mth.lerp(u, face.uv[0], face.uv[2]), Mth.lerp(u, face.uv[6], face.uv[4]));
                        float t = Mth.lerp(v, Mth.lerp(u, face.uv[1], face.uv[3]), Mth.lerp(u, face.uv[7], face.uv[5]));
                        out.addVertex((float) point.x, (float) point.y, (float) point.z).setUv(s, t)
                                .setColor(r, g, b, 255).setNormal(nx, ny, nz);
                    }
                }
            }
        }
        return out.build();
    }

    private static void visibility(Beam beam) {
        int first = Integer.MAX_VALUE;
        int last = -1;
        var pixels = beam.visibility.getPixels();
        for (int i = beam.changedFaces.nextSetBit(0); i >= 0; i = beam.changedFaces.nextSetBit(i + 1)) {
            int slot = beam.slots[i];
            if (slot < 0) {
                continue;
            }
            Face face = beam.faces.get(i);
            for (int cell = 0; cell < 4; cell++) {
                byte lit = (byte) (face != null && face.lit(cell % 2, cell / 2) ? 1 : 0);
                int at = slot + cell;
                if (beam.cells[at] == lit) {
                    continue;
                }
                if (beam.cells[at] == 1) {
                    beam.litCells--;
                }
                beam.litCells += lit;
                beam.cells[at] = lit;
                pixels.setPixelRGBA(at % 256, at / 256, lit == 1 ? -1 : 0);
                first = Math.min(first, at / 256);
                last = Math.max(last, at / 256);
            }
        }
        if (last >= first) {
            beam.visibility.bind();
            pixels.upload(0, 0, first, 0, first, 256, last - first + 1, false, false);
        }
        beam.empty = beam.litCells == 0;
        beam.changedFaces.clear();
    }

    /** Draws this frame's lit surfaces; LightingRenderer.effects, with the beams. */
    public static void drawSurfaces() {
        SURFACES.forEach(Runnable::run);
        SURFACES.clear();
    }

    public static boolean pending() {
        return !SURFACES.isEmpty();
    }

    /** After the frame: changes seen this frame are done with, and lamps no longer drawn freed. */
    public static void endFrame() {
        SURFACES.clear();
        DIRTY_SECTIONS.clear();
        DIRTY_COLUMNS.clear();
        frame++;
        for (Iterator<Map.Entry<StageLightBlock.Entity, Beam>> it = BEAMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<StageLightBlock.Entity, Beam> entry = it.next();
            if (entry.getKey().isRemoved() || frame - entry.getValue().drawn > FORGET_FRAMES) {
                entry.getValue().close();
                it.remove();
            }
        }
        int remaining = SCAN_BUDGET;
        while (remaining > 0 && !SCANS.isEmpty()) {
            SurfaceScan scan = SCANS.removeFirst();
            if (scan.result.isCancelled()) {
                continue;
            }
            int limit = Math.min(64, remaining);
            remaining -= limit;
            try {
                scan.advance(limit);
                if (scan.positions.hasNext()) {
                    SCANS.addLast(scan);
                } else {
                    scan.result.complete(scan.finish());
                }
            } catch (RuntimeException e) {
                scan.result.complete(failed(e));
            }
        }
    }

    /** A block in section ({@code x}, {@code y}, {@code z}) changed (LevelRendererMixin). */
    public static void sectionChanged(int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        DIRTY_SECTIONS.add(key);
        LightingRaycast.sectionChanged(key);
    }

    /** Blocks between these corners, inclusive, changed (LevelRendererMixin). */
    public static void blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int y = minY >> 4; y <= maxY >> 4; y++) {
                for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                    sectionChanged(x, y, z);
                }
            }
        }
    }

    /** Chunk column ({@code x}, {@code z}) was loaded anew (LevelRendererMixin). */
    public static void chunkChanged(int x, int z) {
        DIRTY_COLUMNS.add(ChunkPos.asLong(x, z));
        LightingRaycast.chunkChanged(x, z);
    }

    /** Whether any block changed since the last frame (see sectionChanged). */
    public static boolean blocksChanged() {
        return !DIRTY_SECTIONS.isEmpty() || !DIRTY_COLUMNS.isEmpty();
    }

    /** Frames drawn so far. */
    public static long frame() {
        return frame;
    }

    static void setShader(ShaderInstance loaded) {
        shader = loaded;
    }

    static void forgetAll() {
        LightingRaycast.forgetAll();
        BEAMS.values().forEach(Beam::close);
        BEAMS.clear();
        SCANS.clear();
        SURFACES.clear();
    }

    private static Pose3dc renderPose(Level level, BlockPos pos) {
        var owner = SableCompanion.INSTANCE.getContaining(level, pos);
        if (owner instanceof ClientSubLevelAccess client) {
            return client.renderPose();
        }
        return owner == null ? null : owner.logicalPose();
    }

    static Vec3 sample(Vec3[] points, float u, float v, Vec3 offset) {
        return new Vec3(
                Mth.lerp(v, Mth.lerp(u, points[0].x, points[1].x), Mth.lerp(u, points[3].x, points[2].x)) + offset.x,
                Mth.lerp(v, Mth.lerp(u, points[0].y, points[1].y), Mth.lerp(u, points[3].y, points[2].y)) + offset.y,
                Mth.lerp(v, Mth.lerp(u, points[0].z, points[1].z), Mth.lerp(u, points[3].z, points[2].z)) + offset.z);
    }

    private static boolean visible(LightingRaycast rays, Vec3 from, Vec3 to) {
        LightingRaycast.Hit hit = rays.clip(from, to);
        return hit.block().getType() == HitResult.Type.MISS || hit.point().distanceToSqr(to) < 1e-8;
    }

    private record Key(long pos, int quad) {
    }

    /** The faces a scan found around {@code center}, as Beam keeps them. */
    private record Scan(List<Face> faces, double[] centers, BlockPos center, int radius, Direction.Axis axis) {
    }

    private static volatile boolean warned;

    private static Scan failed(Throwable error) {
        if (!warned) {
            // Once: a chunk changing mid-read can throw now and then, and only skips that scan.
            warned = true;
            LogUtils.getLogger().warn("A stage light couldn't find the faces it reaches", error);
        }
        return null;
    }

    /**
     * The faces around {@code center}, those of {@code previous} found again kept as they are (what the light saw of
     * them with them). The new ones are traced in the background from {@code from} under {@code sight}.
     */
    // ponytail: off the render thread it reads the live client level, as DisplayLight's scan does; a chunk changing
    // mid-read can throw, which skips that scan (failed) and tries again next frame.
    private static Scan scan(Level level, BlockPos lamp, BlockPos center, int radius, Direction.Axis axis,
                             List<Face> previous, LightingRaycast rays, Vec3 origin, Vec3 from, Vec3 direction,
                             double tangent, float range, long sight, int budget) {
        SurfaceScan scan = new SurfaceScan(level, lamp, center, radius, axis, previous, false);
        scan.advance(Integer.MAX_VALUE);
        int remaining = budget;
        for (Face face : scan.created) {
            Geometry still = face.still;
            // Turned away from the light, a face can't be lit: as a trace to it would find, through its own block.
            boolean facing = still.normal.dot(origin.subtract(still.middle)) > 0;
            if (facing) {
                if (remaining < face.seen.length
                        || exposure(still.middle.subtract(origin), direction, tangent, range, LEAD) <= 0) {
                    continue;
                }
                remaining -= face.seen.length;
            }
            for (int i = 0; i < face.seen.length; i++) {
                face.seen[i] = (byte) (facing && rays.visible(from, still.rays[i]) ? 1 : -1);
            }
            face.sight = sight;
            face.untraced = false;
        }
        return scan.finish();
    }

    private static double[] centers(List<Face> faces) {
        double[] result = new double[faces.size() * 6];
        for (int i = 0; i < faces.size(); i++) {
            Geometry still = faces.get(i).still;
            result[i * 6] = still.middle.x;
            result[i * 6 + 1] = still.middle.y;
            result[i * 6 + 2] = still.middle.z;
            result[i * 6 + 3] = still.normal.x;
            result[i * 6 + 4] = still.normal.y;
            result[i * 6 + 5] = still.normal.z;
        }
        return result;
    }

    private static final class SurfaceScan {
        final Level level;
        final BlockPos lamp;
        final BlockPos center;
        final int radius;
        final Direction.Axis axis;
        final boolean sable;
        final SubLevelAccess owner;
        final Vec3 lampOffset;
        final Map<Key, Face> known = new HashMap<>();
        final RandomSource random = RandomSource.create(42);
        final Iterator<BlockPos> positions;
        final List<Face> found = new ArrayList<>();
        final List<Face> created = new ArrayList<>();
        final CompletableFuture<Scan> result = new CompletableFuture<>();

        SurfaceScan(Level level, BlockPos lamp, BlockPos center, int radius, Direction.Axis axis,
                    List<Face> previous, boolean sable) {
            this.level = level;
            this.lamp = lamp;
            this.center = center.immutable();
            this.radius = radius;
            this.axis = axis;
            this.sable = sable;
            owner = sable ? SableCompanion.INSTANCE.getContaining(level, center) : null;
            lampOffset = Vec3.atLowerCornerOf(lamp);
            for (Face face : previous) {
                if (face != null) {
                    known.put(new Key(face.pos.asLong(), face.quad), face);
                }
            }
            int x = axis == Direction.Axis.X ? 2 : radius;
            int y = axis == Direction.Axis.Y ? 2 : radius;
            int z = axis == Direction.Axis.Z ? 2 : radius;
            positions = BlockPos.betweenClosed(center.offset(-x, -y, -z), center.offset(x, y, z)).iterator();
        }

        void advance(int budget) {
            for (int read = 0; read < budget && positions.hasNext(); read++) {
                read(positions.next());
            }
        }

        Scan finish() {
            return new Scan(found, centers(found), center, radius, axis);
        }

        private void read(BlockPos pos) {
            if (!level.isLoaded(pos) || pos.equals(lamp)
                    || sable && SableCompanion.INSTANCE.getContaining(level, pos) != owner) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL) {
                return;
            }
            var mc = Minecraft.getInstance();
            var model = mc.getBlockRenderer().getBlockModel(state);
            Vec3 offset = Vec3.atLowerCornerOf(pos).add(state.getOffset(level, pos));
            int index = 0;
            for (int side = 0; side <= 6; side++) {
                Direction direction = side == 6 ? null : Direction.from3DDataValue(side);
                if (direction != null && !Block.shouldRenderFace(state, level, pos, direction, pos.relative(direction))) {
                    continue;
                }
                random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, direction, random, ModelData.EMPTY, null)) {
                    int[] data = quad.getVertices();
                    Face before = known.get(new Key(pos.asLong(), index));
                    if (before != null && before.state == state && Arrays.equals(before.vertices, data)) {
                        found.add(before);
                        index++;
                        continue;
                    }
                    int tint = quad.isTinted() ? mc.getBlockColors().getColor(state, level, pos, quad.getTintIndex()) : 0xFFFFFF;
                    Vec3[] points = new Vec3[4];
                    float[] uv = new float[8];
                    for (int i = 0; i < 4; i++) {
                        int base = i * IQuadTransformer.STRIDE;
                        points[i] = offset.add(Float.intBitsToFloat(data[base + IQuadTransformer.POSITION]),
                                Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 1]),
                                Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 2]));
                        uv[i * 2] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0]);
                        uv[i * 2 + 1] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]);
                    }
                    Vec3 normal = Vec3.atLowerCornerOf(quad.getDirection().getNormal());
                    double winding = Math.signum(points[1].subtract(points[0]).cross(points[3].subtract(points[0])).dot(normal));
                    Face face = new Face(pos.immutable(), state, index++, data, points, uv, winding, tint, lampOffset);
                    found.add(face);
                    created.add(face);
                }
            }
        }
    }
}
