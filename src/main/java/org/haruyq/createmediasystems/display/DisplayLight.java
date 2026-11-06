package org.haruyq.createmediasystems.display;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * A playing display lights the blocks in front of it like a light source, in its video's colours: each face it reaches
 * is drawn again, its own texture times the video's blurred colour nearest it, added to what's there
 * (shaders/core/display_light). It casts no shadows: their block-sized edges stood out more than the light did.
 */
final class DisplayLight {
    /** How far from the picture, in blocks, the light reaches. */
    static final float RANGE = 10;
    /** Its brightness at a Blu-ray player's 100% lighting. */
    static final float STRENGTH = 0.7f;
    /** Ticks between looks at which faces the light reaches. */
    private static final int REFRESH_TICKS = 20;
    /** Per vertex: x, y, z from the display's block; atlas u, v; video u, v; brightness. */
    private static final int FLOATS = 8;
    private static ShaderInstance shader;
    private static final Map<DisplayBlock.Entity, Lit> LIT = new WeakHashMap<>();
    /** The video's u and v ride in the vertex colour's red and green, its brightness in alpha. */
    private static final Function<ResourceLocation, RenderType> TYPE = Util.memoize(video -> RenderType.create(
            "create_media_systems_display_light", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> shader))
                    .setTextureState(RenderStateShard.MultiTextureStateShard.builder()
                            .add(InventoryMenu.BLOCK_ATLAS, false, true).add(video, true, true).build())
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false)));

    /** The faces last found, kept on screen while a background look for the next ones runs. */
    private static final class Lit {
        long at = Long.MIN_VALUE;
        volatile float[] vertices = new float[0];
        volatile boolean busy;
    }

    private DisplayLight() {
    }

    static void setShader(ShaderInstance loaded) {
        shader = loaded;
    }

    static void render(DisplayBlock.Entity display, DisplayBlock.Screen screen, float width, float height,
                       Matrix4f matrix, MultiBufferSource buffers, ResourceLocation video, float strength) {
        Level level = display.getLevel();
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        Lit lit = LIT.computeIfAbsent(display, d -> new Lit());
        if (!lit.busy && (now - lit.at >= REFRESH_TICKS || now < lit.at)) {
            lit.at = now;
            lit.busy = true;
            BlockPos at = display.getBlockPos();
            Lit target = lit;
            CompletableFuture.supplyAsync(() -> faces(level, at, screen, width, height), Util.backgroundExecutor())
                    .whenComplete((found, error) -> {
                        if (found != null) {
                            target.vertices = found;
                        }
                        target.busy = false;
                    });
        }
        VertexConsumer out = buffers.getBuffer(TYPE.apply(video));
        float[] v = lit.vertices;
        for (int i = 0; i < v.length; i += FLOATS) {
            out.addVertex(matrix, v[i], v[i + 1], v[i + 2]).setColor(v[i + 5], v[i + 6], 0, v[i + 7] * strength)
                    .setUv(v[i + 3], v[i + 4]);
        }
    }

    // ponytail: runs on a background thread and reads the live client level, like vanilla's section rebuilds do; a
    // chunk changing mid-read can throw, which just skips that refresh. Rescans everything once a second; update on
    // block changes instead if the CPU time matters.
    private static float[] faces(Level level, BlockPos at, DisplayBlock.Screen screen, float width, float height) {
        AABB box = new AABB(screen.at(-RANGE, -RANGE, -(float) DisplayBlock.DEPTH),
                screen.at(width + RANGE, height + RANGE, RANGE));
        BlockPos min = at.offset(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ));
        BlockPos max = at.offset(Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ));
        var blocks = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        FloatArrayList out = new FloatArrayList();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL || pos.equals(at)) {
                continue;
            }
            BakedModel model = blocks.getBlockModel(state);
            Vec3 offset = Vec3.atLowerCornerOf(pos.subtract(at));
            for (Direction side : Direction.values()) {
                if (!Block.shouldRenderFace(state, level, pos, side, pos.relative(side))) {
                    continue;
                }
                random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, side, random, ModelData.EMPTY, null)) {
                    light(quad, side, offset, screen, width, height, out);
                }
            }
        }
        return out.toFloatArray();
    }

    /** Adds {@code quad}'s four vertices, lit, to {@code out}, if the light reaches it. */
    private static void light(BakedQuad quad, Direction side, Vec3 offset, DisplayBlock.Screen screen, float width,
                              float height, FloatArrayList out) {
        int[] data = quad.getVertices();
        Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
        float[] vertices = new float[4 * FLOATS];
        boolean reached = false;
        for (int k = 0; k < 4; k++) {
            int base = k * IQuadTransformer.STRIDE;
            Vec3 p = offset.add(Float.intBitsToFloat(data[base + IQuadTransformer.POSITION]),
                    Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 1]),
                    Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 2]));
            Vec3 local = p.subtract(screen.origin());
            double x = local.dot(screen.right());
            double y = local.y;
            Vec3 toLight = screen.at((float) Mth.clamp(x, 0, width), (float) Mth.clamp(y, 0, height), 0).subtract(p);
            double distance = toLight.length();
            double falloff = 1 - Math.min(distance / RANGE, 1);
            double facing = distance < 1e-4 ? 1 : Math.max(0, normal.dot(toLight) / distance);
            // In front of the wall the display hangs on; the wall itself has the glow.
            boolean front = local.dot(screen.out()) > -DisplayBlock.DEPTH + DisplayRenderer.GLOW_LIFT;
            float brightness = front ? (float) (facing * falloff * falloff) : 0;
            reached |= brightness > 0;
            int i = k * FLOATS;
            vertices[i] = (float) p.x;
            vertices[i + 1] = (float) p.y;
            vertices[i + 2] = (float) p.z;
            vertices[i + 3] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0]);
            vertices[i + 4] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]);
            vertices[i + 5] = (float) Mth.clamp(x / width, 0, 1);
            vertices[i + 6] = (float) (1 - Mth.clamp(y / height, 0, 1));
            vertices[i + 7] = brightness;
        }
        if (reached) {
            out.addElements(out.size(), vertices);
        }
    }
}
