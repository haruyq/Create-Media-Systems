package org.haruyq.createmediasystems.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Quads of one render type drawn among others (text, other parts): the buffer for {@code type} is asked for again
 * each quad, as another type asked for between may have ended its batch. Draw in whole quads, 4 vertices together.
 */
public final class QuadOutput implements VertexConsumer {
    /** Opaque, unlit, position and colour, both sides: printed panels, screens and lenses. */
    public static final RenderType FLAT = RenderType.create("create_media_systems_lighting_controls",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    private final MultiBufferSource buffers;
    private final RenderType type;
    private VertexConsumer current;
    private int vertices;

    public QuadOutput(MultiBufferSource buffers, RenderType type) {
        this.buffers = buffers;
        this.type = type;
    }

    /** The buffer as it is now, to draw several quads with nothing else between. */
    public VertexConsumer buffer() {
        return buffers.getBuffer(type);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if ((vertices++ & 3) == 0) {
            current = buffers.getBuffer(type);
        }
        current.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        current.setColor(r, g, b, a);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        current.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        current.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        current.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        current.setNormal(x, y, z);
        return this;
    }
}
