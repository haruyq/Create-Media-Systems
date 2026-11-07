package org.haruyq.createmediasystems.display;

import java.nio.ByteBuffer;
import java.util.ArrayList;
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

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
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

/**
 * A playing display lights the room in front of it as a cinema screen does: an area light the size of the screen, its
 * colours the video's (shaders/core/display_light). Each block face it reaches is drawn again, added to what's there:
 * lit by the part of the picture it faces, shaded softly by blocks between, with some of the screen's light bounced
 * about the room on faces turned away; and, if glossy (glass, ice, polished stone, metal, water), mirroring the
 * picture, blurred by its roughness. Under a shader pack, no reflections (the pack draws its own), and the light is
 * added once the pack has finished the frame.
 * The faces are found on a background thread about once a second and kept in a vertex buffer, drawn each frame.
 */
final class DisplayLight {
    /** How far from the picture, in blocks, the light reaches at least; a big screen's reaches further (see range). */
    private static final float MIN_RANGE = 16;
    private static final float MAX_RANGE = 64;
    /** Its brightness at a Blu-ray player's 100% lighting. */
    static final float STRENGTH = 0.7f;
    /** Ticks between looks at which faces the light reaches, at least. */
    private static final int REFRESH_TICKS = 20;
    /** Rays from all faces' corners to the screen, at most, per look: fewer per corner in a big room. */
    private static final int RAY_BUDGET = 200_000;
    /** Frames a display may go undrawn before its buffers are freed. */
    private static final int FORGET_FRAMES = 600;
    /*
     * Per vertex, in the display's block space: x, y, z; atlas u, v; as its colour, roughness, F0, how much of the
     * screen it sees, whether a block's face (1) or a water surface (0); its normal in the screen's space.
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
    private static final Map<DisplayBlock.Entity, Lit> LIT = new HashMap<>();
    /** Water surfaces lit this frame, drawn once the water itself is (see stage). */
    private static final List<Runnable> WATER = new ArrayList<>();
    /** Under a shader pack, the light drawn this frame, drawn once the pack has finished the frame (see stage). */
    private static final List<Runnable> AFTER_PACK = new ArrayList<>();
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

    /** A display's faces, last found, in vertex buffers; a background look for the next ones; and its light. */
    private static final class Lit {
        /** When it last looked; at first, long enough ago to look now (Long.MIN_VALUE overflowed the subtraction). */
        long at = -REFRESH_TICKS;
        volatile boolean busy;
        /** Found on the background thread, not yet uploaded (faces, water). Guarded by this. */
        @Nullable
        private Mesh[] found;
        /** Freed: what's found for it from now is freed too. Guarded by this. */
        private boolean closed;
        @Nullable
        VertexBuffer faces;
        @Nullable
        VertexBuffer water;
        int facesHash;
        int waterHash;
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
            shader.safeGetUniform("ToScreen").set(toScreen);
            shader.safeGetUniform("CameraPos").set(camera.x, camera.y, camera.z);
            shader.safeGetUniform("ScreenSize").set(width, height);
            shader.safeGetUniform("Range").set(range);
            shader.safeGetUniform("Strength").set(strength);
            shader.safeGetUniform("Specular").set(specular ? 1f : 0f);
        }

        /** Found on the background thread: kept for the next upload, or freed if this light is gone. */
        synchronized void found(Mesh[] meshes) {
            if (closed) {
                Mesh.close(meshes);
                return;
            }
            if (found != null) {
                Mesh.close(found);
            }
            found = meshes;
        }

        /** Its new faces, if found, into its vertex buffers. Render thread. */
        void upload() {
            Mesh[] next;
            synchronized (this) {
                next = found;
                found = null;
            }
            if (next == null) {
                return;
            }
            faces = upload(faces, next[0], facesHash);
            facesHash = next[0].hash;
            water = upload(water, next[1], waterHash);
            waterHash = next[1].hash;
        }

        /** Vertices the same as last time (nothing in the room changed) are left as they are in the buffer. */
        @Nullable
        private static VertexBuffer upload(@Nullable VertexBuffer buffer, Mesh mesh, int hash) {
            try (mesh) {
                if (buffer != null && mesh.data != null && mesh.hash == hash) {
                    return buffer;
                }
                if (mesh.data == null) {
                    if (buffer != null) {
                        buffer.close();
                    }
                    return null;
                }
                VertexBuffer to = buffer != null ? buffer : new VertexBuffer(VertexBuffer.Usage.STATIC);
                to.bind();
                to.upload(mesh.data);
                VertexBuffer.unbind();
                return to;
            }
        }

        /** Draws {@code buffer}, its vertices in the display's block space, with the display at {@code modelView}. */
        void draw(@Nullable VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
            if (buffer == null || shader == null) {
                return;
            }
            type.setupRenderState();
            buffer.bind();
            buffer.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            type.clearRenderState();
        }

        void close() {
            synchronized (this) {
                closed = true;
                if (found != null) {
                    Mesh.close(found);
                    found = null;
                }
            }
            if (faces != null) {
                faces.close();
            }
            if (water != null) {
                water.close();
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
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(DisplayLight::stage);
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
        lit.drawn = frame;
        lit.upload();
        if (!lit.busy && (now - lit.at >= REFRESH_TICKS || now < lit.at)) {
            lit.at = now;
            lit.busy = true;
            BlockPos at = display.getBlockPos();
            CompletableFuture.supplyAsync(() -> faces(level, at, screen, width, height, range), Workers.LIGHT)
                    .whenComplete((found, error) -> {
                        if (found != null) {
                            lit.found(found);
                        } else if (!warned) {
                            // Once: a chunk changing mid-read can throw now and then, and only skips that look.
                            warned = true;
                            LogUtils.getLogger().warn("A display's light couldn't find the faces it reaches", error);
                        }
                        lit.busy = false;
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
        if (lit.specular) {
            lit.draw(lit.faces, modelView, projection);
        } else {
            // A pack takes what is drawn in the world as surfaces' colour and lights it itself: at night, this light
            // all but vanished. Added to the finished frame instead, against the world's depth (as
            // DisplayRenderer.overdraw).
            AFTER_PACK.add(() -> lit.draw(lit.faces, modelView, projection));
        }
        if (lit.specular && lit.water != null) {
            WATER.add(() -> lit.draw(lit.water, modelView, projection));
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
            if (!AFTER_PACK.isEmpty()) {
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
                AFTER_PACK.forEach(Runnable::run);
                AFTER_PACK.clear();
            }
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

    // ponytail: runs on a background thread and reads the live client level, like vanilla's section rebuilds do; a
    // chunk changing mid-read can throw, which just skips that refresh. Rescans everything each time; update on block
    // changes instead if the CPU time matters.
    private static Mesh[] faces(Level level, BlockPos at, DisplayBlock.Screen screen, float width, float height,
                                float range) {
        Vec3 lo = screen.at(-range, -range, -(float) DisplayBlock.DEPTH);
        Vec3 hi = screen.at(width + range, height + range, range);
        BlockPos min = at.offset(Mth.floor(Math.min(lo.x, hi.x)), Mth.floor(Math.min(lo.y, hi.y)),
                Mth.floor(Math.min(lo.z, hi.z)));
        BlockPos max = at.offset(Mth.floor(Math.max(lo.x, hi.x)), Mth.floor(Math.max(lo.y, hi.y)),
                Mth.floor(Math.max(lo.z, hi.z)));
        var blocks = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        Lighting faces = new Lighting(screen, width, height, range);
        Lighting water = new Lighting(screen, width, height, range);
        try {
            return faces(level, at, min, max, blocks, random, faces, water, screen, width, height);
        } catch (Throwable failed) {
            faces.bytes.close();
            water.bytes.close();
            throw failed;
        }
    }

    private static Mesh[] faces(Level level, BlockPos at, BlockPos min, BlockPos max, BlockRenderDispatcher blocks,
                                RandomSource random, Lighting faces, Lighting water, DisplayBlock.Screen screen,
                                float width, float height) {
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
                                    block(level, at, pos, blocks, random, faces, water);
                                }
                            }
                        }
                    }
                }
            }
        }
        Shade shade = new Shade(level, at, screen, width, height, faces.size() + water.size());
        Mesh found = faces.finish(shade);
        try {
            return new Mesh[] {found, water.finish(shade)};
        } catch (Throwable failed) {
            found.close();
            throw failed;
        }
    }

    private static void block(Level level, BlockPos at, BlockPos pos, BlockRenderDispatcher blocks,
                              RandomSource random, Lighting faces, Lighting water) {
        Vec3 offset = Vec3.atLowerCornerOf(pos.subtract(at));
        FluidState fluid = level.getFluidState(pos);
        if (fluid.is(FluidTags.WATER) && !level.getFluidState(pos.above()).is(FluidTags.WATER)) {
            float top = fluid.getHeight(level, pos);
            Vec3[] corners = {offset.add(0, top, 0), offset.add(0, top, 1), offset.add(1, top, 1), offset.add(1, top, 0)};
            water.add(corners, new float[8], Direction.UP, 0, 0.04f, 0.02f);
        }
        BlockState state = level.getBlockState(pos);
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

    /** The lit faces found: their vertices, and the corners still to shade (see Shade). */
    private static final class Lighting {
        private final DisplayBlock.Screen screen;
        private final float width;
        private final float height;
        private final float range;
        final ByteBufferBuilder bytes = new ByteBufferBuilder(1024 * FORMAT.getVertexSize());
        private final BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, FORMAT);
        private final List<Vec3> points = new ArrayList<>();
        private final List<Vec3> normals = new ArrayList<>();

        Lighting(DisplayBlock.Screen screen, float width, float height, float range) {
            this.screen = screen;
            this.width = width;
            this.height = height;
            this.range = range;
        }

        int size() {
            return points.size();
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
                // How much of the screen it sees (blue) is shaded in once every face is found, see finish.
                out.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(uv[2 * k], uv[2 * k + 1])
                        .setColor(roughness, f0, 1, kind).setNormal((float) n.x, (float) n.y, (float) n.z);
                points.add(p);
                normals.add(normal);
            }
        }

        /** Its vertices, each shaded by how much of the screen it sees; their memory is the caller's to free. */
        Mesh finish(Shade shade) {
            MeshData mesh = out.build();
            if (mesh != null) {
                ByteBuffer vertices = mesh.vertexBuffer();
                int stride = FORMAT.getVertexSize();
                int seen = FORMAT.getOffset(VertexFormatElement.COLOR) + 2;
                for (int i = 0; i < points.size(); i++) {
                    vertices.put(i * stride + seen, (byte) (int) (shade.seen(points.get(i), normals.get(i)) * 255));
                }
            }
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

    /**
     * How much of the screen each point sees past the blocks around it: rays to points spread over the picture,
     * through blocks that hide what's behind them (stairs and slabs as whole blocks). Per vertex, so the shade is soft
     * across a face rather than block-edged; fewer rays per point in a big room, to keep each look to RAY_BUDGET.
     */
    private static final class Shade {
        private final Level level;
        private final BlockPos at;
        private final Vec3[] targets;
        private final Long2BooleanOpenHashMap opaque = new Long2BooleanOpenHashMap();
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final Map<Vec3, Float> seen = new HashMap<>();

        Shade(Level level, BlockPos at, DisplayBlock.Screen screen, float width, float height, int points) {
            this.level = level;
            this.at = at;
            int across = Mth.clamp(Math.round(width / 2), 3, 6);
            int up = Mth.clamp(Math.round(height / 2), 2, 4);
            Vec3[] all = new Vec3[across * up];
            for (int i = 0; i < across; i++) {
                for (int j = 0; j < up; j++) {
                    all[i * up + j] = screen.at(width * (i + 0.5f) / across, height * (j + 0.5f) / up, 0.05f);
                }
            }
            // Corners are shared by about four faces each.
            int rays = Mth.clamp(RAY_BUDGET * 4 / Math.max(points, 1), 2, all.length);
            targets = new Vec3[rays];
            for (int k = 0; k < rays; k++) {
                targets[k] = all[k * all.length / rays];
            }
        }

        /** 0..1 of the screen's points {@code p}, on a face turned {@code normal}, has a clear line to. */
        float seen(Vec3 p, Vec3 normal) {
            return seen.computeIfAbsent(p.add(normal.scale(0.02)), from -> {
                int rays = 0;
                int clear = 0;
                for (Vec3 to : targets) {
                    if (normal.dot(to.subtract(p)) <= 0) {
                        continue;
                    }
                    rays++;
                    if (!blocked(from, to)) {
                        clear++;
                    }
                }
                return rays == 0 ? 1f : clear / (float) rays;
            });
        }

        /** Whether a block hiding what's behind it stands between {@code from} and {@code to}, in block space (DDA). */
        private boolean blocked(Vec3 from, Vec3 to) {
            Vec3 d = to.subtract(from);
            int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
            int ex = Mth.floor(to.x), ey = Mth.floor(to.y), ez = Mth.floor(to.z);
            int sx = (int) Math.signum(d.x), sy = (int) Math.signum(d.y), sz = (int) Math.signum(d.z);
            double tx = step(from.x, d.x, x), ty = step(from.y, d.y, y), tz = step(from.z, d.z, z);
            double dx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / d.x);
            double dy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / d.y);
            double dz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / d.z);
            for (int guard = 0; guard < 1024; guard++) {
                if (tx < ty && tx < tz) {
                    x += sx;
                    tx += dx;
                } else if (ty < tz) {
                    y += sy;
                    ty += dy;
                } else {
                    z += sz;
                    tz += dz;
                }
                if (Math.min(tx, Math.min(ty, tz)) > 1 || (x == ex && y == ey && z == ez)) {
                    return false;
                }
                if (opaque(x, y, z)) {
                    return true;
                }
            }
            return false;
        }

        /** Where along {@code from + t·d}, t in 0..1, the first cell boundary from cell {@code cell} is. */
        private static double step(double from, double d, int cell) {
            if (d > 0) {
                return (cell + 1 - from) / d;
            }
            return d < 0 ? (cell - from) / d : Double.MAX_VALUE;
        }

        private boolean opaque(int x, int y, int z) {
            if (x == 0 && y == 0 && z == 0) {
                return false;
            }
            long key = BlockPos.asLong(at.getX() + x, at.getY() + y, at.getZ() + z);
            if (opaque.containsKey(key)) {
                return opaque.get(key);
            }
            boolean blocks = level.getBlockState(cursor.set(key)).canOcclude();
            opaque.put(key, blocks);
            return blocks;
        }
    }
}
