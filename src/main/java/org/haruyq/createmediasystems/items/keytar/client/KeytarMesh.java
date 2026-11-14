package org.haruyq.createmediasystems.items.keytar.client;

import java.io.IOException;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.Worn;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The AX-Edge mesh is authored in metres, with +X toward the neck and +Z out of the keys. */
public final class KeytarMesh {
    private static final Kind RANGE = Kind.KEYTAR;
    private static final ResourceLocation GEOMETRY = CreateMediaSystems.id("models/item/ax_edge/ax_edge.mesh.json");
    private static final ResourceLocation TEXTURE = CreateMediaSystems.id("textures/item/ax_edge/black_red.png");
    private static final RenderType BODY = RenderType.entityCutoutNoCull(TEXTURE);
    private static final RenderType LAMPS = RenderType.entityTranslucentEmissive(TEXTURE, false);
    @Nullable private static volatile Rig rig;

    @FunctionalInterface
    public interface Keys {
        float travel(int note);
    }

    record Quad(float[][] vertices, float[] normal, boolean emissive) {}
    record Part(String name, String motion, int index, float[] pivot, float angle, float[] front,
                float travel, boolean accidental, List<Quad> quads) {}
    record Mount(float[] translation, float[] rotation, List<float[]> strap) {
        Matrix4f pose() {
            return new Matrix4f().translation(translation[0], translation[1], translation[2])
                    .rotateXYZ(rotation[0] * Mth.DEG_TO_RAD, rotation[1] * Mth.DEG_TO_RAD, rotation[2] * Mth.DEG_TO_RAD);
        }
    }
    record Geometry(int version, String units, @SerializedName("key_count") int keyCount, List<Part> parts,
                    Mount front, Mount back) {}
    record Rig(List<Part> parts, Part[] keys, Matrix4f front, Matrix4f back, List<float[]> frontStrap,
               List<float[]> backStrap, Vector3f tailPin, Vector3f neckPin) {}

    private KeytarMesh() {}

    public static void reload(ResourceManager resources) {
        try (var reader = resources.openAsReader(GEOMETRY)) {
            rig = bake(new Gson().fromJson(reader, Geometry.class));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Could not load " + GEOMETRY, error);
        }
    }

    static Rig bake(Geometry geometry) {
        if (geometry.version != 1 || !"metres".equals(geometry.units) || geometry.keyCount != RANGE.keys
                || geometry.front.strap.size() != 7 || geometry.back.strap.size() != 7) {
            throw new IllegalArgumentException("Incompatible AX-Edge rig");
        }
        Part[] keys = new Part[RANGE.keys];
        for (Part part : geometry.parts) {
            if (!"key".equals(part.motion)) continue;
            if (part.index < 0 || part.index >= keys.length || keys[part.index] != null) {
                throw new IllegalArgumentException("Invalid AX-Edge key " + part.name);
            }
            keys[part.index] = part;
        }
        for (Part key : keys) {
            if (key == null) throw new IllegalArgumentException("AX-Edge rig is missing a key");
        }

        Matrix4f front = geometry.front.pose(), back = geometry.back.pose();
        var frontStrap = geometry.front.strap;
        Matrix4f inverse = new Matrix4f(front).invert();
        Vector3f tail = inverse.transformPosition(new Vector3f(frontStrap.getFirst()));
        Vector3f neck = inverse.transformPosition(new Vector3f(frontStrap.getLast()));
        return new Rig(geometry.parts, keys, front.scale(Worn.METRE), back.scale(Worn.METRE), frontStrap,
                geometry.back.strap, tail, neck);
    }

    @Nullable static Rig rig() { return rig; }

    public static float keyX(int note) {
        var key = key(note);
        return key == null ? 0 : key.front[0];
    }

    static Vector3f touch(int note, float handX, float press) {
        var key = key(note);
        if (key == null) return new Vector3f(handX, .10f, .05f);
        return new Vector3f(handX, key.front[1] - .023f, key.front[2] + .027f - key.travel * press);
    }

    @Nullable private static Part key(int note) {
        var model = rig;
        int index = note - RANGE.first;
        return model == null || index < 0 || index >= model.keys.length ? null : model.keys[index];
    }

    public static void draw(PoseStack pose, MultiBufferSource buffers, int light, int overlay,
            Keys keys, boolean powered, float bend, int program) {
        var model = rig;
        if (model == null) return;
        draw(pose, buffers.getBuffer(BODY), model.parts, light, overlay, keys, powered, bend, program, false);
        if (powered) draw(pose, buffers.getBuffer(LAMPS), model.parts, Mesh.FULL_BRIGHT, overlay, keys, true, bend, program, true);
    }

    private static void draw(PoseStack pose, VertexConsumer out, List<Part> parts, int light, int overlay,
            Keys keys, boolean powered, float bend, int program, boolean emissive) {
        for (Part part : parts) {
            if ("program_led".equals(part.motion) && (!powered || part.index != program)) continue;
            float amount = travel(part, keys, powered, bend);
            if (amount != 0) {
                pose.pushPose();
                move(pose, part, amount);
            }
            for (Quad quad : part.quads) {
                if (quad.emissive != emissive) continue;
                float[] normal = quad.normal;
                for (float[] v : quad.vertices) {
                    Mesh.vertex(pose, out, v[0], v[1], v[2], v[3], v[4], 0xFFFFFF, normal[0], normal[1], normal[2], light, overlay);
                }
            }
            if (amount != 0) pose.popPose();
        }
    }

    private static float travel(Part part, Keys keys, boolean powered, float bend) {
        if (part.motion == null) return 0;
        return switch (part.motion) {
            case "key" -> keys.travel(RANGE.first + part.index);
            case "switch" -> powered ? 1 : 0;
            case "bar", "ribbon" -> Math.abs(bend);
            default -> 0;
        };
    }

    static void move(PoseStack pose, Part part, float amount) {
        switch (part.motion) {
            case "key" -> {
                float[] pivot = part.pivot;
                pose.translate(pivot[0], pivot[1], pivot[2]);
                pose.mulPose(Axis.XP.rotationDegrees(part.angle * amount));
                pose.translate(-pivot[0], -pivot[1], -pivot[2]);
            }
            case "switch" -> pose.translate(.006f * amount, 0, 0);
            case "bar", "ribbon" -> pose.translate(0, 0, -.0025f * amount);
            default -> { }
        }
    }
}
