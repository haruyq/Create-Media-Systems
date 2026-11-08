package org.haruyq.createmediasystems.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.haruyq.createmediasystems.client.Workers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * A playing display lights the room in front of it as a cinema screen does: an area light the size of the screen, its
 * colours the video's (shaders/core/display_light). Each block face it reaches is drawn again, added to what's there:
 * lit by the part of the picture it faces, shaded softly by blocks between, with some of the screen's light bounced
 * about the room on faces turned away; and, if glossy (glass, ice, polished stone, metal, water), mirroring the
 * picture, blurred by its roughness. Under a shader pack, no reflections (the pack draws its own), and the light is
 * added once the pack has finished the frame.
 * The faces are found in the background when blocks change and kept in a vertex buffer, drawn each frame.
 */
public final class DisplayLight {
    /** How far from the picture, in blocks, the light reaches at least; a big screen's reaches further (see range). */
    private static final float MIN_RANGE = 16;
    private static final float MAX_RANGE = 64;
    /** Its brightness at a Blu-ray player's 100% lighting. */
    static final float STRENGTH = 0.7f;
    /** Ticks between looks at which faces the light reaches, at least. */
    private static final int REFRESH_TICKS = 20;
    /** Chooses fewer shadow samples per vertex in a big room; DisplayShadows bounds their work per frame. */
    private static final int RAY_BUDGET = 200_000;
    /** Frames a display may go undrawn before its buffers are freed. */
    private static final int FORGET_FRAMES = 600;
    /*
     * Per vertex, in the display's block space: x, y, z; atlas u, v; as its colour, roughness, F0, unused,
     * whether a block's face (1) or a water surface (0); its normal in the screen's space.
     */
    /** POSITION_TEX_COLOR_NORMAL's layout, its own instance: Iris extends some vanilla formats by identity. */
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    private static ShaderInstance shader;
    private static int shaderVersion;
    private static final Map<DisplayBlock.Entity, Lit> LIT = new HashMap<>();
    /** Water surfaces lit this frame, drawn once the water itself is (see stage). */
    private static final List<Runnable> WATER = new ArrayList<>();
    private static CompletableFuture<Void> scanning = CompletableFuture.completedFuture(null);
    private static final Map<Block, float[]> MATERIALS = new ConcurrentHashMap<>();
    private static final float[] MATTE_MATERIAL = {1, 0.04f};
    private static int frame;
    /**
     * Each side's quads, and then those on no side (null): everything of a model not flush with its block's faces,
     * such as a seat's cushion and back or iron bars' middles.
     */
    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};
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

    /** A display's faces, last found, in vertex buffers; a background look for the next ones; and its light. */
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
        int drawn;
        /** As drawn this frame. */
        final Matrix4f toScreen = new Matrix4f();
        final Vector3f camera = new Vector3f();
        float width;
        float height;
        float range;
        float strength;
        boolean specular;
        ResourceLocation video;
        final RenderType type = RenderType.create("create_media_systems_display_light", FORMAT,
                VertexFormat.Mode.QUADS, 256, false, false, RenderType.CompositeState.builder()
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
            RenderSystem.setShaderTexture(1, video);
            RenderSystem.setShaderTexture(2, current.shadows.texture());
            shader.safeGetUniform("ToScreen").set(toScreen);
            shader.safeGetUniform("CameraPos").set(camera.x, camera.y, camera.z);
            shader.safeGetUniform("ScreenSize").set(width, height);
            shader.safeGetUniform("Range").set(range);
            shader.safeGetUniform("Strength").set(strength);
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
            if (part == 0 && pending != null && pending.shadows.advance(frame, pending.buffers, pending.counts)) {
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
            current.buffers[part].drawWithShader(modelView, projection, shader);
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

    private record Found(Mesh[] meshes, DisplayShadows.Grid grid, Vec3[] targets, DisplayBlock.Screen screen,
                         int hash) implements AutoCloseable {
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
                shadows = new DisplayShadows(found.grid, found.targets, found.screen, counts[0] + counts[1]);
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
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel().isClientSide()) {
                ChunkPos pos = event.getChunk().getPos();
                chunkChanged(pos.x, pos.z);
            }
        });
    }

    public static void blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (Lit lit : LIT.values()) {
            if (lit.bounds != null && lit.bounds.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                lit.refresh.invalidate();
            }
        }
    }

    public static void chunkChanged(int x, int z) {
        blocksChanged(x << 4, Integer.MIN_VALUE, z << 4, (x << 4) + 15, Integer.MAX_VALUE, (z << 4) + 15);
    }

    /** How far a screen {@code width} × {@code height} blocks lights: a room a few times its size, as a real one does. */
    static float range(double width, double height) {
        return Mth.clamp((float) Math.max(width, height) * 2.5f, MIN_RANGE, MAX_RANGE);
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
        // Under a shader pack, its reflections are the pack's to draw.
        lit.specular = !IrisCompat.shaderPackInUse();
        lit.video = video;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        if (level != Minecraft.getInstance().level || !ScreenLight.afterPack(() -> lit.draw(0, modelView, projection))) {
            lit.draw(0, modelView, projection);
        }
        if (lit.specular && lit.current != null && lit.current.buffers[1] != null) {
            WATER.add(() -> lit.draw(1, modelView, projection));
        }
    }

    /**
     * Water is drawn after block entities, see-through over what's beneath it: a reflection on it drawn with them
     * would be mostly covered. (With Fabulous graphics, water still covers it some.) And the buffers of displays no
     * longer drawn are freed.
     */
    private static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            WATER.forEach(Runnable::run);
            WATER.clear();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            WATER.clear();
            frame++;
            for (Iterator<Map.Entry<DisplayBlock.Entity, Lit>> it = LIT.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<DisplayBlock.Entity, Lit> entry = it.next();
                if (entry.getKey().isRemoved() || frame - entry.getValue().drawn > FORGET_FRAMES) {
                    entry.getValue().close();
                    it.remove();
                }
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
        var blocks = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        Lighting faces = new Lighting(screen, width, height, range);
        Lighting water = new Lighting(screen, width, height, range);
        DisplayShadows.Grid grid = new DisplayShadows.Grid(bounds.min.subtract(at), bounds.max.subtract(at));
        try {
            return faces(level, at, bounds.min, bounds.max, blocks, random, faces, water, screen, width, height, grid);
        } catch (Throwable failed) {
            faces.bytes.close();
            water.bytes.close();
            grid.close();
            throw failed;
        }
    }

    private static Found faces(Level level, BlockPos at, BlockPos min, BlockPos max, BlockRenderDispatcher blocks,
                                RandomSource random, Lighting faces, Lighting water, DisplayBlock.Screen screen,
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
                    // Most of a room is air: whole sections of it are skipped.
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
                                    block(level, at, pos, blocks, random, faces, water, grid);
                                }
                            }
                        }
                    }
                }
            }
        }
        Vec3[] targets = shadeTargets(screen, width, height, faces.size() + water.size());
        Mesh found = faces.finish();
        try {
            Mesh wet = water.finish();
            int hash = ((found.hash * 31 + wet.hash) * 31 + grid.cells.hashCode()) * 31 + Arrays.hashCode(targets);
            return new Found(new Mesh[] {found, wet}, grid, targets, screen, hash);
        } catch (Throwable failed) {
            found.close();
            throw failed;
        }
    }

    private static void block(Level level, BlockPos at, BlockPos pos, BlockRenderDispatcher blocks,
                              RandomSource random, Lighting faces, Lighting water, DisplayShadows.Grid grid) {
        Vec3 offset = Vec3.atLowerCornerOf(pos.subtract(at));
        FluidState fluid = level.getFluidState(pos);
        if (fluid.is(FluidTags.WATER) && !level.getFluidState(pos.above()).is(FluidTags.WATER)) {
            float top = fluid.getHeight(level, pos);
            Vec3[] corners = {offset.add(0, top, 0), offset.add(0, top, 1), offset.add(1, top, 1), offset.add(1, top, 0)};
            water.add(corners, new float[8], Direction.UP, 0, 0.04f, 0.02f);
        }
        BlockState state = level.getBlockState(pos);
        if (state.canOcclude()) {
            grid.block(pos.getX() - at.getX(), pos.getY() - at.getY(), pos.getZ() - at.getZ());
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        BakedModel model = blocks.getBlockModel(state);
        float[] material = material(state);
        for (Direction side : SIDES) {
            if (side != null && !Block.shouldRenderFace(state, level, pos, side, pos.relative(side))) {
                continue;
            }
            random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, side, random, ModelData.EMPTY, null)) {
                int[] data = quad.getVertices();
                Vec3[] corners = new Vec3[4];
                float[] uv = new float[8];
                for (int k = 0; k < 4; k++) {
                    int base = k * IQuadTransformer.STRIDE;
                    corners[k] = offset.add(Float.intBitsToFloat(data[base + IQuadTransformer.POSITION]),
                            Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 1]),
                            Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 2]));
                    uv[2 * k] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0]);
                    uv[2 * k + 1] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]);
                }
                faces.add(corners, uv, quad.getDirection(), 1, material[0], material[1]);
            }
        }
    }

    /** The lit faces, whose shadows are computed from the block grid once uploaded. */
    private static final class Lighting {
        private final DisplayBlock.Screen screen;
        private final float width;
        private final float height;
        private final float range;
        final ByteBufferBuilder bytes = new ByteBufferBuilder(1024 * FORMAT.getVertexSize());
        private final BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, FORMAT);
        private int count;

        Lighting(DisplayBlock.Screen screen, float width, float height, float range) {
            this.screen = screen;
            this.width = width;
            this.height = height;
            this.range = range;
        }

        int size() {
            return count;
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
            for (int k = 0; k < 4; k++) {
                Vec3 p = corners[k];
                out.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(uv[2 * k], uv[2 * k + 1])
                        .setColor(roughness, f0, 1, kind).setNormal((float) n.x, (float) n.y, (float) n.z);
                count++;
            }
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

    static Vec3[] shadeTargets(DisplayBlock.Screen screen, float width, float height, int points) {
        int across = Mth.clamp(Math.round(width / 2), 3, 6);
        int up = Mth.clamp(Math.round(height / 2), 2, 4);
        // Corners are shared by about four faces each.
        int rays = Mth.clamp(RAY_BUDGET * 4 / Math.max(points, 1), 2, across * up);
        Vec3[] targets = new Vec3[rays];
        int bits = 32 - Integer.numberOfLeadingZeros(rays - 1);
        int next = 0;
        // Bit reversal spreads even the smallest budgets across both dimensions without changing between scans.
        for (int k = 0; k < (1 << bits); k++) {
            int row = Integer.reverse(k) >>> (32 - bits);
            if (row < rays) {
                targets[next] = screen.at(width * (next + 0.5f) / rays, height * (row + 0.5f) / rays, 0.05f);
                next++;
            }
        }
        return targets;
    }
}
