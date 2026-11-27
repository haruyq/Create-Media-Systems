package org.haruyq.createmediasystems.blocks.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL20;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import org.haruyq.createmediasystems.client.ScreenLight;
import org.haruyq.createmediasystems.client.SurfaceLight;
import org.haruyq.createmediasystems.client.IrisCompat;
import org.haruyq.createmediasystems.client.Workers;
import org.haruyq.createmediasystems.client.WorldEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;

/**
 * A playing display lights the room in front of it as a cinema screen does: an area light the size of the screen, its
 * colours the video's (shaders/core/display_light). Each block face it reaches is drawn again, added to what's there:
 * lit by the part of the picture it faces, shaded softly by what stands between, and by the screen's light off the
 * faces about it, in their colours, and by the mobs and players in it; and, if glossy (glass, ice, polished stone, metal, water), mirroring the
 * picture, blurred by its roughness. Under a shader pack, no reflections (the pack draws its own), and the light is
 * added once the pack has finished the frame.
 * The faces are found in the background when blocks change and kept in a vertex buffer, drawn each frame.
 */
public final class DisplayLight {
    /** How far from the picture, in blocks, the light is drawn at least; a big screen's is further (see range). */
    private static final float MIN_RANGE = 16;
    private static final float MAX_RANGE = 64;
    /** Its brightness at a Blu-ray player's 100% lighting. */
    static final float STRENGTH = 0.7f;
    /** The mobs and players whose shadows a light draws, at most (display_light.fsh's Bodies). */
    private static final int BODIES = 8;
    /** Ticks between looks at which faces the light reaches, at least. */
    private static final int REFRESH_TICKS = 20;
    /** Frames a display may go undrawn before its buffers are freed. */
    private static final int FORGET_FRAMES = 600;
    /*
     * Per vertex, in the display's block space: x, y, z; atlas u, v; as its colour, roughness, F0, which way its
     * face's middle is (Lighting.add), whether a block's face (1) or a water surface (0); its normal in the screen's
     * space.
     */
    private static ShaderInstance shader;
    private static int shaderVersion;
    private static final Map<DisplayBlock.Entity, Lit> LIT = new HashMap<>();
    /** Water surfaces lit this frame, drawn once the water itself is (see stage). */
    private static final List<Runnable> WATER = new ArrayList<>();
    private static CompletableFuture<Void> scanning = CompletableFuture.completedFuture(null);
    private static final Map<Block, float[]> MATERIALS = new ConcurrentHashMap<>();
    private static final float[] MATTE_MATERIAL = {1, 0.04f};
    private static final Map<BlockState, Long> SHAPES = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    static final class Refresh {
        private long last = -REFRESH_TICKS;
        private boolean dirty = true;
        private boolean busy;

        synchronized void invalidate() {
            dirty = true;
        }

        synchronized boolean begin(long now) {
            if (busy || !dirty || now >= last && now - last < REFRESH_TICKS) {
                return false;
            }
            dirty = false;
            busy = true;
            last = now;
            return true;
        }

        synchronized void finish(boolean success) {
            busy = false;
            dirty |= !success;
        }
    }

    record Bounds(BlockPos min, BlockPos max) {
        boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            return min.getX() <= maxX && max.getX() >= minX && min.getY() <= maxY && max.getY() >= minY
                    && min.getZ() <= maxZ && max.getZ() >= minZ;
        }
    }

    private static final class Lit {
        final Refresh refresh = new Refresh();
        Bounds bounds;
        BlockPos at;
        Direction facing;
        /** Found on the background thread, not yet uploaded (faces, water). Guarded by this. */
        @Nullable
        private Found found;
        /** Freed: what's found for it from now is freed too. Guarded by this. */
        private volatile boolean closed;
        @Nullable
        private Surface current;
        @Nullable
        private Surface pending;
        long drawn;
        /** As drawn this frame. */
        final Matrix4f toScreen = new Matrix4f();
        final Vector3f camera = new Vector3f();
        float width;
        float height;
        float range;
        float strength;
        /** The bodies in the light, each's box in the screen's space: its least corner, then its greatest. */
        final float[] bodies = new float[BODIES * 6];
        int bodyCount;
        boolean specular;
        ResourceLocation video;
        final RenderType type = SurfaceLight.type("create_media_systems_display_light", () -> shader, this::setup);

        private void setup() {
            Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).setFilter(false, true);
            RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
            RenderSystem.setShaderTexture(1, video);
            RenderSystem.setShaderTexture(2, current.shadows.texture());
            current.shadows.bindVoxels(shader);
            shader.safeGetUniform("ToScreen").set(toScreen);
            shader.safeGetUniform("CameraPos").set(camera.x, camera.y, camera.z);
            shader.safeGetUniform("PictureSize").set(width, height);
            shader.safeGetUniform("Range").set(range);
            shader.safeGetUniform("Strength").set(strength);
            shader.safeGetUniform("BodyCount").set(bodyCount);
            shader.safeGetUniform("Specular").set(specular ? 1f : 0f);
        }

        /** Found on the background thread: kept for the next upload, or freed if this light is gone. */
        synchronized void found(Found next) {
            if (closed) {
                next.close();
                return;
            }
            if (found != null) {
                found.close();
            }
            found = next;
        }

        void upload() {
            if (pending != null) {
                return;
            }
            Found next;
            synchronized (this) {
                next = found;
                found = null;
            }
            if (next != null) {
                try (next) {
                    if (current == null || current.hash != next.hash || current.version != shaderVersion) {
                        pending = new Surface(next);
                    }
                }
            }
        }

        void draw(int part, Matrix4f modelView, Matrix4f projection) {
            if (closed || shader == null) {
                return;
            }
            if (part == 0 && pending != null
                    && pending.shadows.advance(WorldEvents.frame(), pending.buffers, pending.counts)) {
                if (current != null) {
                    current.close();
                }
                current = pending;
                pending = null;
            }
            if (current == null || current.buffers[part] == null) {
                return;
            }
            type.setupRenderState();
            shader.safeGetUniform("VisibilityOffset").set(part == 0 ? 0 : current.counts[0]);
            current.buffers[part].bind();
            // As VertexBuffer.drawWithShader, but for Bodies: an array, which a shader's JSON can't declare.
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            GL20.glUniform3fv(GL20.glGetUniformLocation(shader.getId(), "Bodies"), bodies);
            current.buffers[part].draw();
            shader.clear();
            VertexBuffer.unbind();
            type.clearRenderState();
        }

        void close() {
            synchronized (this) {
                closed = true;
                if (found != null) {
                    found.close();
                    found = null;
                }
            }
            if (current != null) {
                current.close();
            }
            if (pending != null) {
                pending.close();
            }
        }
    }

    private record Found(Mesh[] meshes, DisplayShadows.Grid grid, DisplayBlock.Screen screen, float width,
                         float height, int hash) implements AutoCloseable {
        @Override
        public void close() {
            Mesh.close(meshes);
            grid.close();
        }
    }

    private static final class Surface implements AutoCloseable {
        final VertexBuffer[] buffers = new VertexBuffer[2];
        final int[] counts = new int[2];
        final int hash;
        final int version = shaderVersion;
        DisplayShadows shadows;

        Surface(Found found) {
            hash = found.hash;
            try {
                for (int i = 0; i < buffers.length; i++) {
                    MeshData data = found.meshes[i].data;
                    if (data != null) {
                        counts[i] = data.drawState().vertexCount();
                        buffers[i] = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        buffers[i].bind();
                        buffers[i].upload(data);
                    }
                }
                shadows = new DisplayShadows(found.grid, found.screen, found.width, found.height,
                        counts[0] + counts[1]);
            } catch (Throwable error) {
                close();
                throw error;
            } finally {
                VertexBuffer.unbind();
            }
        }

        @Override
        public void close() {
            for (VertexBuffer buffer : buffers) {
                if (buffer != null) {
                    buffer.close();
                }
            }
            if (shadows != null) {
                shadows.close();
            }
        }
    }

    /** Vertices built off the render thread, in native memory (no large arrays for the GC), till uploaded. */
    private record Mesh(ByteBufferBuilder bytes, @Nullable MeshData data, int hash) implements AutoCloseable {
        static void close(Mesh[] meshes) {
            for (Mesh mesh : meshes) {
                mesh.close();
            }
        }

        @Override
        public void close() {
            if (data != null) {
                data.close();
            }
            bytes.close();
        }
    }

    private DisplayLight() {
    }

    static void setShader(ShaderInstance loaded) {
        shader = loaded;
        shaderVersion++;
        MATERIALS.clear();
        for (Lit lit : LIT.values()) {
            lit.refresh.invalidate();
            if (lit.pending != null) {
                lit.pending.close();
                lit.pending = null;
            }
        }
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(DisplayLight::stage);
        WorldEvents.onBlocksChanged(DisplayLight::blocksChanged);
        WorldEvents.onChunkLoaded(DisplayLight::chunkChanged);
        WorldEvents.onChunkUnloaded(DisplayLight::chunkChanged);
        WorldEvents.onFrameEnded(DisplayLight::frameEnded);
    }

    private static void blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (Lit lit : LIT.values()) {
            if (lit.bounds != null && lit.bounds.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                lit.refresh.invalidate();
            }
        }
    }

    private static void chunkChanged(int x, int z) {
        blocksChanged(x << 4, Integer.MIN_VALUE, z << 4, (x << 4) + 15, Integer.MAX_VALUE, (z << 4) + 15);
    }

    /**
     * How far a screen {@code width} × {@code height} blocks is drawn lighting: to where its light, falling off with
     * the square of the distance and growing with its area, is too little to see (display_light.fsh's reach).
     */
    static float range(double width, double height) {
        return Mth.clamp((float) Math.sqrt(width * height) * 6, MIN_RANGE, MAX_RANGE);
    }

    static void render(DisplayBlock.Entity display, DisplayBlock.Screen screen, float width, float height,
                       Matrix4f matrix, ResourceLocation video, float strength) {
        Level level = display.getLevel();
        if (level == null) {
            return;
        }
        float range = range(width, height);
        long now = level.getGameTime();
        Lit lit = LIT.computeIfAbsent(display, d -> new Lit());
        long frame = WorldEvents.frame();
        if (!display.getBlockPos().equals(lit.at) || lit.width != width || lit.height != height || lit.facing != display.facing()
                || frame - lit.drawn > 1) {
            lit.at = display.getBlockPos().immutable();
            lit.bounds = bounds(display.getBlockPos(), screen, width, height, range);
            lit.facing = display.facing();
            lit.refresh.invalidate();
        }
        lit.drawn = frame;
        lit.upload();
        if (lit.refresh.begin(now)) {
            BlockPos at = display.getBlockPos();
            Bounds bounds = lit.bounds;
            scanning = scanning.thenApplyAsync(ignored -> lit.closed ? null
                            : faces(level, at, bounds, screen, width, height, range), Workers.LIGHT)
                    .handle((found, error) -> {
                        if (found != null) {
                            lit.found(found);
                        } else if (error != null && !warned) {
                            // Once: a chunk changing mid-read can throw now and then, and only skips that look.
                            warned = true;
                            LogUtils.getLogger().warn("A display's light couldn't find the faces it reaches", error);
                        }
                        lit.refresh.finish(error == null);
                        return null;
                    });
        }
        // The display's block space to the screen's own: x across the picture, y up, z out of it.
        Vec3 o = screen.origin();
        Vec3 r = screen.right();
        Vec3 n = screen.out();
        lit.toScreen.identity()
                .setRow(0, new Vector4f((float) r.x, (float) r.y, (float) r.z, (float) -r.dot(o)))
                .setRow(1, new Vector4f(0, 1, 0, (float) -o.y))
                .setRow(2, new Vector4f((float) n.x, (float) n.y, (float) n.z, (float) -n.dot(o)));
        // The camera, at the origin of where it draws from (the display's pose, a Sable sub-level's included).
        new Matrix4f(lit.toScreen).mul(new Matrix4f(matrix).invert()).transformPosition(0, 0, 0, lit.camera);
        lit.width = width;
        lit.height = height;
        lit.range = range;
        lit.strength = strength;
        lit.bodyCount = bodies(level, lit.toScreen, matrix, range, lit.bodies);
        // Under a shader pack, its reflections are the pack's to draw.
        lit.specular = !IrisCompat.shaderPackInUse();
        lit.video = video;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        if (!WorldEvents.isClientWorld(level) || !ScreenLight.afterPack(() -> lit.draw(0, modelView, projection))) {
            lit.draw(0, modelView, projection);
        }
        if (lit.specular && lit.current != null && lit.current.buffers[1] != null) {
            WATER.add(() -> lit.draw(1, modelView, projection));
        }
    }

    /**
     * The mobs and players the light reaches, for their shadows: each's box, in the screen's space, into {@code out};
     * the nearest to the display, if more than fit. None in a world that isn't the client's (a Ponder scene).
     */
    private static int bodies(Level level, Matrix4f toScreen, Matrix4f matrix, float range, float[] out) {
        if (!WorldEvents.isClientWorld(level)) {
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        // matrix draws the display's blocks from the camera: back through it, the world to the screen's space.
        Matrix4f toLight = new Matrix4f(toScreen).mul(new Matrix4f(matrix).invert());
        Vec3 display = camera.add(new Vec3(matrix.transformPosition(new Vector3f())));
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(display, 2 * range, 2 * range, 2 * range), body -> !body.isSpectator() && !body.isInvisible());
        found.sort(Comparator.comparingDouble(body -> body.distanceToSqr(display)));
        int count = Math.min(found.size(), BODIES);
        Vector3f corner = new Vector3f();
        for (int i = 0; i < count; i++) {
            LivingEntity body = found.get(i);
            // Where it is drawn this frame, between its ticks.
            AABB box = body.getBoundingBox().move(body.getPosition(partial).subtract(body.position()))
                    .move(camera.reverse());
            Arrays.fill(out, 6 * i, 6 * i + 3, Float.MAX_VALUE);
            Arrays.fill(out, 6 * i + 3, 6 * i + 6, -Float.MAX_VALUE);
            for (int c = 0; c < 8; c++) {
                toLight.transformPosition((float) ((c & 1) == 0 ? box.minX : box.maxX),
                        (float) ((c & 2) == 0 ? box.minY : box.maxY), (float) ((c & 4) == 0 ? box.minZ : box.maxZ),
                        corner);
                for (int k = 0; k < 3; k++) {
                    out[6 * i + k] = Math.min(out[6 * i + k], corner.get(k));
                    out[6 * i + 3 + k] = Math.max(out[6 * i + 3 + k], corner.get(k));
                }
            }
        }
        return count;
    }

    /**
     * Water is drawn after block entities, see-through over what's beneath it: a reflection on it drawn with them
     * would be mostly covered. (With Fabulous graphics, water still covers it some.)
     */
    private static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            WATER.forEach(Runnable::run);
            WATER.clear();
        }
    }

    private static void frameEnded() {
        WATER.clear();
        long frame = WorldEvents.frame();
        for (Iterator<Map.Entry<DisplayBlock.Entity, Lit>> it = LIT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<DisplayBlock.Entity, Lit> entry = it.next();
            if (entry.getKey().isRemoved() || frame - entry.getValue().drawn > FORGET_FRAMES) {
                entry.getValue().close();
                it.remove();
            }
        }
    }

    static Bounds bounds(BlockPos at, DisplayBlock.Screen screen, float width, float height, float range) {
        Vec3 lo = screen.at(-range, -range, -(float) DisplayBlock.DEPTH);
        Vec3 hi = screen.at(width + range, height + range, range);
        BlockPos min = at.offset(Mth.floor(Math.min(lo.x, hi.x)), Mth.floor(Math.min(lo.y, hi.y)),
                Mth.floor(Math.min(lo.z, hi.z)));
        BlockPos max = at.offset(Mth.floor(Math.max(lo.x, hi.x)), Mth.floor(Math.max(lo.y, hi.y)),
                Mth.floor(Math.max(lo.z, hi.z)));
        return new Bounds(min, max);
    }

    // shortcut: a chunk changing during the background read can fail the scan; the next render retries it.
    private static Found faces(Level level, BlockPos at, Bounds bounds, DisplayBlock.Screen screen, float width,
                                float height, float range) {
        RandomSource random = RandomSource.create();
        Lighting faces = new Lighting(screen, width, height, range);
        Lighting water = new Lighting(screen, width, height, range);
        DisplayShadows.Grid grid = new DisplayShadows.Grid(bounds.min.subtract(at), bounds.max.subtract(at));
        try {
            return faces(level, at, bounds.min, bounds.max, random, faces, water, screen, width, height, grid);
        } catch (Throwable failed) {
            faces.bytes.close();
            water.bytes.close();
            grid.close();
            throw failed;
        }
    }

    private static Found faces(Level level, BlockPos at, BlockPos min, BlockPos max, RandomSource random, Lighting faces, Lighting water, DisplayBlock.Screen screen,
                                float width, float height, DisplayShadows.Grid grid) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = Math.max(min.getY(), level.getMinBuildHeight());
        int maxY = Math.min(max.getY(), level.getMaxBuildHeight() - 1);
        for (int cx = SectionPos.blockToSectionCoord(min.getX()); cx <= SectionPos.blockToSectionCoord(max.getX()); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(min.getZ()); cz <= SectionPos.blockToSectionCoord(max.getZ()); cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                for (int cy = SectionPos.blockToSectionCoord(minY); cy <= SectionPos.blockToSectionCoord(maxY); cy++) {
                    if (chunk.getSection(chunk.getSectionIndexFromSectionY(cy)).hasOnlyAir()) {
                        continue;
                    }
                    int x0 = Math.max(min.getX(), cx << 4), x1 = Math.min(max.getX(), (cx << 4) + 15);
                    int y0 = Math.max(minY, cy << 4), y1 = Math.min(maxY, (cy << 4) + 15);
                    int z0 = Math.max(min.getZ(), cz << 4), z1 = Math.min(max.getZ(), (cz << 4) + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int y = y0; y <= y1; y++) {
                            for (int z = z0; z <= z1; z++) {
                                pos.set(x, y, z);
                                if (!pos.equals(at)) {
                                    block(level, at, pos, random, faces, water, grid);
                                }
                            }
                        }
                    }
                }
            }
        }
        Mesh found = faces.finish();
        try {
            Mesh wet = water.finish();
            int hash = Objects.hash(found.hash, wet.hash, grid.cells, Arrays.hashCode(grid.shapes), screen, width,
                    height);
            return new Found(new Mesh[] {found, wet}, grid, screen, width, height, hash);
        } catch (Throwable failed) {
            found.close();
            throw failed;
        }
    }

    private static void block(Level level, BlockPos at, BlockPos pos, RandomSource random, Lighting faces,
                              Lighting water, DisplayShadows.Grid grid) {
        Vec3 offset = Vec3.atLowerCornerOf(pos.subtract(at));
        FluidState fluid = level.getFluidState(pos);
        if (fluid.is(FluidTags.WATER) && !level.getFluidState(pos.above()).is(FluidTags.WATER)) {
            float top = fluid.getHeight(level, pos);
            Vec3[] corners = {offset.add(0, top, 0), offset.add(0, top, 1), offset.add(1, top, 1), offset.add(1, top, 0)};
            water.add(corners, new float[8], Direction.UP, 0, 0.04f, 0.02f);
        }
        BlockState state = level.getBlockState(pos);
        long shape = shape(level, pos, state);
        if (shape != 0) {
            grid.block(pos.getX() - at.getX(), pos.getY() - at.getY(), pos.getZ() - at.getZ(), shape,
                    state.getMapColor(level, pos).id);
        }
        float[] material = material(state);
        SurfaceLight.forEachQuad(level, pos, state, random, quad -> {
            Vec3[] corners = new Vec3[4];
            float[] uv = new float[8];
            SurfaceLight.read(quad, offset, corners, uv);
            faces.add(corners, uv, quad.getDirection(), 1, material[0], material[1]);
        });
    }

    /**
     * What of a block stands in the light's way (DisplayShadows.cells): what one can't walk through, a door, a slab
     * or a fence as much as a whole block, unless it is seen through.
     */
    // ponytail: a state's shape where first met; one that differs by where it stands (bamboo) keeps that.
    private static long shape(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        Long known = SHAPES.get(state);
        if (known != null) {
            return known;
        }
        long[] bits = new long[1];
        if (!(state.getBlock() instanceof DisplayBlock) && state.getRenderShape() != RenderShape.INVISIBLE
                && !state.is(Tags.Blocks.GLASS_BLOCKS) && !state.is(Tags.Blocks.GLASS_PANES)
                && !ItemBlockRenderTypes.getRenderLayers(state).contains(RenderType.translucent())) {
            state.getCollisionShape(level, pos).forAllBoxes((x0, y0, z0, x1, y1, z1) ->
                    bits[0] |= DisplayShadows.cells(x0, y0, z0, x1, y1, z1));
        }
        SHAPES.put(state, bits[0]);
        return bits[0];
    }

    /** The lit faces, whose shadows are computed from the block grid once uploaded. */
    private static final class Lighting {
        private final DisplayBlock.Screen screen;
        private final float width;
        private final float height;
        private final float range;
        final ByteBufferBuilder bytes = new ByteBufferBuilder(1024 * SurfaceLight.FORMAT.getVertexSize());
        private final BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, SurfaceLight.FORMAT);

        Lighting(DisplayBlock.Screen screen, float width, float height, float range) {
            this.screen = screen;
            this.width = width;
            this.height = height;
            this.range = range;
        }

        /**
         * The quad through {@code corners}, if the light reaches it: in front of the screen and near enough. Turned
         * away, it still has the light bounced about the room.
         */
        void add(Vec3[] corners, float[] uv, Direction side, float kind, float roughness, float f0) {
            boolean reached = false;
            for (Vec3 p : corners) {
                Vec3 local = p.subtract(screen.origin());
                double x = local.dot(screen.right());
                double z = local.dot(screen.out());
                Vec3 nearest = screen.at((float) Mth.clamp(x, 0, width), (float) Mth.clamp(local.y, 0, height), 0);
                reached |= z > 0 && p.distanceTo(nearest) < range;
            }
            if (!reached) {
                return;
            }
            Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
            Vec3 n = new Vec3(normal.dot(screen.right()), normal.y, normal.dot(screen.out()));
            Vec3 middle = corners[0].add(corners[1]).add(corners[2]).add(corners[3]).scale(0.25);
            for (int k = 0; k < 4; k++) {
                Vec3 p = corners[k];
                // Which way the face's middle is from here, a third of a byte per axis: a corner's shadow is looked
                // for from just inside its face (display_shadow.vsh), as on the corner itself, where blocks meet,
                // which of them a ray starts in is a toss-up.
                int inward = side(middle.x - p.x) + 3 * side(middle.y - p.y) + 9 * side(middle.z - p.z);
                out.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(uv[2 * k], uv[2 * k + 1])
                        .setColor(roughness, f0, (inward + 0.5f) / 255, kind)
                        .setNormal((float) n.x, (float) n.y, (float) n.z);
            }
        }

        /** 0, 1 or 2: {@code d} below, about or above zero. */
        private static int side(double d) {
            return d < -1e-4 ? 0 : d > 1e-4 ? 2 : 1;
        }

        Mesh finish() {
            MeshData mesh = out.build();
            return new Mesh(bytes, mesh, mesh == null ? 0 : mesh.vertexBuffer().hashCode());
        }
    }

    /**
     * Roughness (0 a mirror, 1 matte) and reflectance head-on (F0; above 0.5, a metal, its reflection tinted by its
     * own colour) of a block's faces.
     */
    // ponytail: by tag and name (polished_, smooth_, ...); a block tag per material if packs want to tune it.
    private static float[] material(BlockState state) {
        return MATERIALS.computeIfAbsent(state.getBlock(), block -> {
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)) {
                return new float[] {0.02f, 0.04f};
            }
            if (block == Blocks.ICE || block == Blocks.BLUE_ICE) {
                return new float[] {0.06f, 0.02f};
            }
            if (state.is(BlockTags.ICE)) {
                return new float[] {0.15f, 0.02f};
            }
            boolean aged = name.contains("exposed") || name.contains("weathered") || name.contains("oxidized");
            if (state.is(Tags.Blocks.STORAGE_BLOCKS_IRON) || state.is(Tags.Blocks.STORAGE_BLOCKS_GOLD)
                    || state.is(Tags.Blocks.STORAGE_BLOCKS_NETHERITE)
                    || (name.contains("copper") && !aged && !name.contains("ore"))) {
                return new float[] {0.3f, 1};
            }
            if (name.startsWith("polished_") || name.contains("quartz") || name.contains("glazed_terracotta")
                    || name.contains("obsidian")) {
                return new float[] {0.3f, 0.04f};
            }
            if (name.startsWith("smooth_") || name.endsWith("_concrete") || name.contains("prismarine")) {
                return new float[] {0.5f, 0.04f};
            }
            return MATTE_MATERIAL;
        });
    }
}
