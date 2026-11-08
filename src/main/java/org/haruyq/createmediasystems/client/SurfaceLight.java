package org.haruyq.createmediasystems.client;

import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;

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
