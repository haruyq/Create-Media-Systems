package org.haruyq.createmediasystems.client.mesh;

import java.util.function.DoubleToIntFunction;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Procedural meshes drawn on one shared material atlas: a 4x4 grid of material {@link Tile}s, one of whose texels is
 * plain enough to carry a vertex colour alone. Quads are given with the normal they face, and their corners are emitted
 * in the winding that faces it, whichever way round they were listed.
 */
public final class Mesh {
    public static final ResourceLocation ATLAS = CreateMediaSystems.id("textures/item/disc_launcher_materials.png");
    /** Packed light for a part that glows by itself. */
    public static final int FULL_BRIGHT = 0xF000F0;
    /** The atlas texel that colour-only quads sample. */
    private static final float PLAIN_U = 12.5f / 64, PLAIN_V = 4.5f / 64;
    /** A tile's inset and size: each 16-texel tile is sampled half a texel inside its edges. */
    private static final float TILE_INSET = 1f / 128, TILE_SIZE = 15f / 64;

    /** The atlas's material tiles, in its row-major order. */
    public enum Tile {
        METAL, DARK, BRASS, WOOD, COPPER, VENTS, DISC, LIGHT;

        public float u() { return (ordinal() % 4) * .25f; }
        public float v() { return (ordinal() / 4) * .25f; }
        int shade() { return this == DARK ? 220 : 255; }
    }

    private Mesh() {}

    /** Opaque parts: they write depth, so translucent sorting can't expose hidden intersecting faces. */
    public static RenderType solid() { return RenderType.entityCutoutNoCull(ATLAS); }

    /** Parts drawn over the solid ones, lit by themselves. */
    public static RenderType emissive() { return RenderType.entityTranslucentEmissive(ATLAS); }

    /** Whether corners a, b, c, d, in that order, wind away from normal n, and must be emitted a, d, c, b. */
    public static boolean reversed(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
            float dx, float dy, float dz, float nx, float ny, float nz) {
        float ux = bx - ax, uy = by - ay, uz = bz - az, vx = cx - ax, vy = cy - ay, vz = cz - az, wx = dx - ax, wy = dy - ay, wz = dz - az;
        return ((uy * vz - uz * vy) + (vy * wz - vz * wy)) * nx + ((uz * vx - ux * vz) + (vz * wx - vx * wz)) * ny
                + ((ux * vy - uy * vx) + (vx * wy - vy * wx)) * nz < 0;
    }

    public static void quad(PoseStack p, VertexConsumer v, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz, int color, int light) {
        vertex(p, v, ax, ay, az, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
        if (reversed(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, ny, nz)) {
            vertex(p, v, dx, dy, dz, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
            vertex(p, v, cx, cy, cz, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
            vertex(p, v, bx, by, bz, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
        } else {
            vertex(p, v, bx, by, bz, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
            vertex(p, v, cx, cy, cz, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
            vertex(p, v, dx, dy, dz, PLAIN_U, PLAIN_V, color, nx, ny, nz, light, OverlayTexture.NO_OVERLAY);
        }
    }

    /** A quad showing a whole material tile, a at its top-left corner and b at its top-right. */
    public static void quad(PoseStack p, VertexConsumer v, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz, Tile tile, int light, int overlay) {
        float u = tile.u() + TILE_INSET, w = tile.v() + TILE_INSET;
        int shade = tile.shade() * 0x010101;
        vertex(p, v, ax, ay, az, u, w, shade, nx, ny, nz, light, overlay);
        if (reversed(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, ny, nz)) {
            vertex(p, v, dx, dy, dz, u, w + TILE_SIZE, shade, nx, ny, nz, light, overlay);
            vertex(p, v, cx, cy, cz, u + TILE_SIZE, w + TILE_SIZE, shade, nx, ny, nz, light, overlay);
            vertex(p, v, bx, by, bz, u + TILE_SIZE, w, shade, nx, ny, nz, light, overlay);
        } else {
            vertex(p, v, bx, by, bz, u + TILE_SIZE, w, shade, nx, ny, nz, light, overlay);
            vertex(p, v, cx, cy, cz, u + TILE_SIZE, w + TILE_SIZE, shade, nx, ny, nz, light, overlay);
            vertex(p, v, dx, dy, dz, u, w + TILE_SIZE, shade, nx, ny, nz, light, overlay);
        }
    }

    /** An axis-aligned cuboid of one colour, all six faces outward. */
    public static void box(PoseStack p, VertexConsumer v, float x0, float y0, float z0, float x1, float y1, float z1, int color, int light) {
        quad(p, v, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, color, light);
        quad(p, v, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, color, light);
        quad(p, v, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, color, light);
        quad(p, v, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, color, light);
        quad(p, v, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, color, light);
        quad(p, v, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, color, light);
    }

    /** An axis-aligned cuboid with a material tile on each of its six outward faces. */
    public static void box(PoseStack p, VertexConsumer v, float x0, float y0, float z0, float x1, float y1, float z1,
            Tile tile, int light, int overlay) {
        quad(p, v, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, tile, light, overlay);
        quad(p, v, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, tile, light, overlay);
        quad(p, v, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, tile, light, overlay);
        quad(p, v, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, tile, light, overlay);
        quad(p, v, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, tile, light, overlay);
        quad(p, v, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, tile, light, overlay);
    }

    /**
     * A flat ring (a disk when {@code inner} is 0) around (x, y) in the plane at z, facing +z or -z by the sign of
     * {@code facing}, in {@code segments} sectors each coloured by the angle it starts at.
     */
    public static void ring(PoseStack p, VertexConsumer v, float x, float y, float z, float outer, float inner, int segments,
            float facing, DoubleToIntFunction color, int light) {
        for (int i = 0; i < segments; i++) {
            double a = i * 2 * Math.PI / segments, b = (i + 1) * 2 * Math.PI / segments;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b);
            quad(p, v, x + outer * ca, y + outer * sa, z, x + outer * cb, y + outer * sb, z, x + inner * cb, y + inner * sb, z,
                    x + inner * ca, y + inner * sa, z, 0, 0, Math.signum(facing), color.applyAsInt(a), light);
        }
    }

    public static void ring(PoseStack p, VertexConsumer v, float x, float y, float z, float outer, float inner, int segments,
            float facing, int color, int light) {
        ring(p, v, x, y, z, outer, inner, segments, facing, angle -> color, light);
    }

    /** One vertex: {@code color} is 0xRRGGBB, and the normal is turned by the pose like the position. */
    public static void vertex(PoseStack p, VertexConsumer v, float x, float y, float z, float u, float w, int color,
            float nx, float ny, float nz, int light, int overlay) {
        v.addVertex(p.last(), x, y, z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255).setUv(u, w)
                .setOverlay(overlay).setLight(light).setNormal(p.last(), nx, ny, nz);
    }
}
