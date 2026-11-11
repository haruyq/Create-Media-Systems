package org.haruyq.createmediasystems.client;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Light drawn over the world's block faces, a display's or a stage light's: how it is drawn, and read off a model. */
public final class SurfaceLight {
    /** POSITION_TEX_COLOR_NORMAL's layout, its own instance: Iris extends some vanilla formats by identity. */
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    /** The sides a model keeps its quads under, in the order they are read; null last, for those never culled. */
    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};

    private SurfaceLight() {
    }

    /**
     * Faces of {@link #FORMAT} drawn by {@code shader}, added onto what is lit, colour only and just off the faces;
     * {@code setup} binds its textures and uniforms as it draws.
     */
    public static RenderType type(String name, Supplier<ShaderInstance> shader, Runnable setup) {
        return RenderType.create(name, FORMAT, VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(shader))
                        .setTextureState(new RenderStateShard.EmptyTextureStateShard(setup, () -> {
                        }))
                        .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                        .createCompositeState(false));
    }

    /**
     * Each quad of {@code state}'s model that is drawn at {@code pos}, those a neighbour hides left out: side by side
     * (SIDES' order), then those never culled. None for a block not drawn as a model. {@code random} is seeded alike
     * for every side, so a model that picks among variants picks the same each time it is read.
     */
    public static void forEachQuad(BlockGetter level, BlockPos pos, BlockState state, RandomSource random,
                                   Consumer<BakedQuad> quads) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        for (Direction side : SIDES) {
            if (side != null && !Block.shouldRenderFace(state, level, pos, side, pos.relative(side))) {
                continue;
            }
            random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, side, random, ModelData.EMPTY, null)) {
                quads.accept(quad);
            }
        }
    }

    /** {@code quad}'s four corners, moved by {@code offset}, into {@code corners}, and their atlas u, v into {@code uv}. */
    public static void read(BakedQuad quad, Vec3 offset, Vec3[] corners, float[] uv) {
        int[] data = quad.getVertices();
        for (int k = 0; k < 4; k++) {
            int base = k * IQuadTransformer.STRIDE;
            corners[k] = offset.add(Float.intBitsToFloat(data[base + IQuadTransformer.POSITION]),
                    Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 1]),
                    Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + 2]));
            uv[2 * k] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0]);
            uv[2 * k + 1] = Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]);
        }
    }
}
