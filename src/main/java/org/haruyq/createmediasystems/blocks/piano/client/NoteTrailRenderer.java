package org.haruyq.createmediasystems.blocks.piano.client;

import java.util.List;
import java.util.function.IntFunction;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.ClientConfig;

public final class NoteTrailRenderer {
    private static final int VERTICAL_STEPS = 7, HALO_STEPS = 2;
    private static final float WORLD_HEIGHT = 1.75f;
    private static final float WHITE_U = 12.5f / 64, WHITE_V = 4.5f / 64;

    public record Key(float x, float y, float z, float width) {}

    private NoteTrailRenderer() {}

    public static boolean enabled() { return ClientConfig.PIANO_NOTE_TRAILS.get(); }
    public static void toggle() {
        ClientConfig.PIANO_NOTE_TRAILS.set(!enabled());
        ClientConfig.PIANO_NOTE_TRAILS.save();
    }

    public static void world(PoseStack pose, MultiBufferSource buffers, List<NoteTrails.Trail> trails,
            float unitsPerBlock, IntFunction<Key> keys) {
        if (!enabled() || trails.isEmpty()) return;
        var out = buffers.getBuffer(Mesh.emissive());
        for (var trail : trails) {
            var key = keys.apply(trail.note());
            float laneWidth = key.width / trail.lanes();
            float x = key.x - key.width / 2 + laneWidth * (trail.lane() + .5f);
            float y0 = key.y + trail.tail() * WORLD_HEIGHT * unitsPerBlock;
            float y1 = key.y + trail.head() * WORLD_HEIGHT * unitsPerBlock;
            if (y1 - y0 < .003f * unitsPerBlock) continue;
            float halfWidth = Math.max(.001f * unitsPerBlock, laneWidth * .37f);
            for (int halo = HALO_STEPS; halo >= 0; halo--) {
                float width = halfWidth * (1 + .6f * halo);
                for (int i = 0; i < VERTICAL_STEPS; i++) {
                    float a = i / (float) VERTICAL_STEPS, b = (i + 1) / (float) VERTICAL_STEPS;
                    float top = lerp(a, y1, y0), bottom = lerp(b, y1, y0);
                    int light = shade(trail, a, halo), base = shade(trail, b, halo);
                    quad(pose, out, x, key.z, width, bottom, top, base, light, false);
                    quad(pose, out, x, key.z, width, bottom, top, base, light, true);
                }
            }
        }
    }

    private static int shade(NoteTrails.Trail trail, float fraction, int halo) {
        float distance = lerp(fraction, trail.head(), trail.tail());
        float envelope = Math.min(1, fraction * 5) * (1 - .72f * distance * distance);
        float tip = 1 - Math.abs(fraction - .21f) * 1.3f;
        float alpha = (.65f + .35f * trail.velocity()) * envelope * (halo == 0 ? .96f : halo == 1 ? .25f : .10f);
        return color(trail.color(), halo == 0 ? .14f + .57f * Math.max(0, tip) : .02f, alpha);
    }

    private static int color(int rgb, float white, float alpha) {
        int r = Math.round(lerp(white, rgb >> 16 & 255, 255));
        int g = Math.round(lerp(white, rgb >> 8 & 255, 255));
        int b = Math.round(lerp(white, rgb & 255, 255));
        return Math.round(255 * alpha) << 24 | r << 16 | g << 8 | b;
    }

    private static float lerp(float fraction, float from, float to) { return from + (to - from) * fraction; }

    private static void quad(PoseStack pose, VertexConsumer out, float x, float z, float width, float bottom, float top,
            int base, int light, boolean cross) {
        vertex(pose, out, cross ? x : x - width, bottom, cross ? z - width : z, base);
        vertex(pose, out, cross ? x : x + width, bottom, cross ? z + width : z, base);
        vertex(pose, out, cross ? x : x + width, top, cross ? z + width : z, light);
        vertex(pose, out, cross ? x : x - width, top, cross ? z - width : z, light);
    }

    private static void vertex(PoseStack pose, VertexConsumer out, float x, float y, float z, int argb) {
        out.addVertex(pose.last(), x, y, z).setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24)
                .setUv(WHITE_U, WHITE_V).setOverlay(OverlayTexture.NO_OVERLAY).setLight(Mesh.FULL_BRIGHT)
                .setNormal(pose.last(), 0, 0, -1);
    }
}
