package org.haruyq.createmediasystems.client.mesh;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Multiplies every vertex's colour by {@code color} (0xRRGGBB) and its alpha by {@code alpha}/255. */
public record Tint(VertexConsumer target, int color, int alpha) implements VertexConsumer {
    @Override public VertexConsumer addVertex(float x, float y, float z) { target.addVertex(x, y, z); return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        target.setColor(r * (color >> 16 & 255) / 255, g * (color >> 8 & 255) / 255, b * (color & 255) / 255, a * alpha / 255);
        return this;
    }
    @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }
}
