package org.haruyq.createmediasystems.blocks.piano.client;

import java.util.List;
import java.util.function.IntFunction;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class NoteTrailRenderer {
    private static final float WORLD_HEIGHT = 1.75f;
    private static final float BOTTOM_UV = .25f, TOP_UV = .75f;
    private static final int SPARKS = 14;
    private static final NoteTrails.Clock CLOCK = new NoteTrails.Clock(System.nanoTime());
    private static final RenderType LIGHT = material("create_media_systems_note_light", RenderStateShard.TRANSLUCENT_TRANSPARENCY, true);
    private static final RenderType HALO = material("create_media_systems_note_halo", RenderStateShard.LIGHTNING_TRANSPARENCY, false);

    public record Key(float x, float y, float z, float width) {}

    private NoteTrailRenderer() {}

    private static RenderType material(String name, RenderStateShard.TransparencyStateShard blend, boolean sort) {
        return RenderType.create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, false, sort,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeEyesShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(
                                CreateMediaSystems.id("textures/piano/note_light.png"), true, false))
                        .setTransparencyState(blend)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    public static boolean enabled() { return ClientConfig.PIANO_NOTE_TRAILS.get(); }
    public static void toggle() {
        ClientConfig.PIANO_NOTE_TRAILS.set(!enabled());
        ClientConfig.PIANO_NOTE_TRAILS.save();
    }

    public static int speed() { return ClientConfig.PIANO_NOTE_SPEED.get(); }
    public static void speed(int percentage) {
        CLOCK.at(System.nanoTime(), percentage);
        ClientConfig.PIANO_NOTE_SPEED.set(percentage);
    }

    public static long time(long now) { return CLOCK.at(now, speed()); }

    public static void world(PoseStack pose, MultiBufferSource buffers, List<NoteTrails.Trail> trails,
            float unitsPerBlock, IntFunction<Key> keys) {
        if (!enabled() || trails.isEmpty()) return;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var right = new Matrix4f(pose.last().pose()).invert().transformDirection(camera.getLeftVector(), new Vector3f()).negate();
        right.y = 0;
        if (right.lengthSquared() < 1e-8) right.set(1, 0, 0);
        else right.normalize();
        var out = buffers.getBuffer(LIGHT);
        float height = WORLD_HEIGHT * unitsPerBlock;
        float seconds = (time(System.nanoTime()) % 20_000_000_000L) / 1_000_000_000f;
        for (var trail : trails) {
            var key = keys.apply(trail.note());
            float lane = key.width / trail.lanes();
            float x = key.x - key.width / 2 + lane * (trail.lane() + .5f);
            float bottom = key.y + trail.tail() * height, top = key.y + trail.head() * height;
            if (top - bottom < .003f * unitsPerBlock) continue;
            float width = Math.max(.0015f * unitsPerBlock, lane * .96f);
            float end = Math.min(width * .73f, (top - bottom) * .5f);
            float glow = .82f + .18f * trail.velocity();
            int root = shade(trail, trail.tail(), glow, .06f), tip = shade(trail, trail.head(), glow, 0);
            float softEdge = width * .24f;
            ribbon(pose, out, right, x, key.z, width, bottom - softEdge, bottom + end, 0, BOTTOM_UV, root, root);
            middle(pose, out, right, x, key.z, width, bottom + end, top - end, root, tip, trail, glow);
            ribbon(pose, out, right, x, key.z, width, top - end, top + softEdge, TOP_UV, 1, tip, tip);
            if (trail.lanes() <= 4 && trails.size() <= 128) {
                sparks(pose, out, right, x, key, width, height, trail, seconds);
            }
        }
        var halo = buffers.getBuffer(HALO);
        for (var trail : trails) {
            if (trail.lanes() > 4 || trails.size() > 128 || trail.head() <= trail.tail()) continue;
            var key = keys.apply(trail.note());
            float lane = key.width / trail.lanes();
            float x = key.x - key.width / 2 + lane * (trail.lane() + .5f);
            float spread = lane * 2.6f;
            float bottom = key.y + trail.tail() * height, top = key.y + trail.head() * height;
            int tint = color(trail.color(), .31f, (.18f + trail.velocity() * .10f) * (1 - trail.tail() * .65f));
            ribbon(pose, halo, right, x, key.z, spread, bottom - spread, top + spread, 0, 1, tint, tint, .5f, 1);
        }
    }

    private static void middle(PoseStack pose, VertexConsumer out, Vector3f right, float x, float z, float width,
            float bottom, float top, int root, int tip, NoteTrails.Trail trail, float glow) {
        if (bottom >= top) return;
        if (trail.lanes() > 4) {
            ribbon(pose, out, right, x, z, width, bottom, top, BOTTOM_UV, TOP_UV, root, tip);
            return;
        }
        float y = bottom;
        int tint = root;
        for (int step = 1; step <= 6; step++) {
            float fraction = step / 6f;
            float nextY = Mth.lerp(fraction, bottom, top);
            float wave = .075f + .07f * Mth.sin(step * 2.2f + trail.head() * 21 + 5 * scramble(trail.seed()));
            int next = step == 6 ? tip : shade(trail, Mth.lerp(fraction, trail.tail(), trail.head()), glow, wave);
            ribbon(pose, out, right, x, z, width, y, nextY, .5f, .5f, tint, next);
            tint = next;
            y = nextY;
        }
    }

    private static void sparks(PoseStack pose, VertexConsumer out, Vector3f right, float x, Key key, float width, float height,
            NoteTrails.Trail trail, float seconds) {
        if (trail.tail() == 0) {
            float size = width * 2.1f;
            float y = key.y + .09f * size;
            int tint = color(trail.color(), .77f, .88f);
            ribbon(pose, out, right, x, key.z, size, y - size, y + size, 0, 1,
                    tint, tint, .5f, 1);
        }
        for (int i = 0; i < SPARKS; i++) {
            int seed = trail.seed() + i * 0x9e3779b9;
            float phase = seconds * 1.25f + scramble(seed);
            phase -= (int) phase;
            float along = Mth.lerp(scramble(seed + 1), trail.tail(), trail.head());
            float opacity = (1 - phase * .8f) * (1 - along * .7f) * (.55f + .45f * trail.velocity());
            float side = (i & 1) == 0 ? 1 : -1;
            float offset = width * side * (.62f + phase * (1.6f + scramble(seed + 2) * .8f));
            float y = key.y + along * height + width * phase * .8f;
            float size = width * (.055f + .090f * scramble(seed + 3));
            int tint = color(trail.color(), .75f, opacity);
            ribbon(pose, out, right, x + offset, key.z, size, y - size, y + size * (2 + phase), 0, 1,
                    tint, tint, .5f, 1);
        }
    }

    private static float scramble(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        return ((value ^ value >>> 15) >>> 8) / 16777216f;
    }

    private static int shade(NoteTrails.Trail trail, float distance, float glow, float shimmer) {
        return color(trail.color(), .64f - .25f * distance + shimmer, glow * (1 - .33f * distance * distance));
    }

    private static int color(int rgb, float white, float alpha) {
        int r = Math.round(Mth.lerp(white, rgb >> 16 & 255, 255));
        int g = Math.round(Mth.lerp(white, rgb >> 8 & 255, 255));
        int b = Math.round(Mth.lerp(white, rgb & 255, 255));
        return Math.round(255 * alpha) << 24 | r << 16 | g << 8 | b;
    }

    private static void ribbon(PoseStack pose, VertexConsumer out, Vector3f right, float x, float z, float width,
            float bottom, float top, float v0, float v1, int base, int tip) {
        ribbon(pose, out, right, x, z, width, bottom, top, v0, v1, base, tip, 0, .5f);
    }

    private static void ribbon(PoseStack pose, VertexConsumer out, Vector3f right, float x, float z, float width,
            float bottom, float top, float v0, float v1, int base, int tip, float u0, float u1) {
        float dx = right.x * width, dz = right.z * width;
        vertex(pose, out, x - dx, bottom, z - dz, u0, v0, base);
        vertex(pose, out, x + dx, bottom, z + dz, u1, v0, base);
        vertex(pose, out, x + dx, top, z + dz, u1, v1, tip);
        vertex(pose, out, x - dx, top, z - dz, u0, v1, tip);
    }

    private static void vertex(PoseStack pose, VertexConsumer out, float x, float y, float z, float u, float v, int argb) {
        out.addVertex(pose.last(), x, y, z).setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(Mesh.FULL_BRIGHT)
                .setNormal(pose.last(), 0, 0, -1);
    }
}
