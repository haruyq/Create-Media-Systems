package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.CellLighting;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;

/** Local colored spill on the actual receiver/skin vertices, including the first-person hand pass. */
public final class CellLight {
    private final Vector3f position;
    private final int color;
    private final float radius, strength;

    public CellLight(Matrix4f pose, float x, float y, float z, float radius, int color, float strength) {
        position = pose.transformPosition(new Vector3f(x, y, z));
        this.radius = pose.transformDirection(new Vector3f(radius, 0, 0)).length();
        this.color = color;
        this.strength = strength;
    }

    public VertexConsumer wrap(VertexConsumer target) { return strength <= 0 ? target : new Lit(target); }
    public MultiBufferSource wrap(MultiBufferSource target) { return type -> wrap(target.getBuffer(type)); }

    private final class Lit implements VertexConsumer {
        private final VertexConsumer target;
        private float x, y, z;
        private int r = 255, g = 255, b = 255, a = 255, block, sky;

        Lit(VertexConsumer target) { this.target = target; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x; this.y = y; this.z = z;
            target.addVertex(x, y, z); return this;
        }
        // Hold these attributes until the normal arrives. BufferBuilder accepts each element once.
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            this.r = r; this.g = g; this.b = b; this.a = a; return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { block = u; sky = v; return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) {
            float dx = position.x - x, dy = position.y - y, dz = position.z - z;
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float facing = distance < .0001f || normalLength < .0001f ? 1
                    : Math.max(0, (dx * nx + dy * ny + dz * nz) / (distance * normalLength));
            float amount = Math.min(1, strength * CellLighting.falloff(distance, radius) * (.24f + .76f * facing));
            target.setColor(CellLighting.channel(r, color >> 16 & 255, amount),
                    CellLighting.channel(g, color >> 8 & 255, amount), CellLighting.channel(b, color & 255, amount), a);
            target.setUv2(Math.max(block, Math.round(amount * 240)), sky);
            target.setNormal(nx, ny, nz);
            return this;
        }
    }
}
