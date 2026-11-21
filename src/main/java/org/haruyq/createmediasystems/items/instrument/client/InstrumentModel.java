package org.haruyq.createmediasystems.items.instrument.client;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.IOException;
import java.io.Reader;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.instrument.InstrumentKind;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Reference meshes use metres and Y up. Instrument rigs keep each moving part separate. */
public final class InstrumentModel {
    public record Rig(float scale, float nut_width, float radius, int frets, List<StringSpec> strings,
                      float[] bridge_pivot, float[][] strap_pins, float[] pick_point,
                      float length, float[] embouchure, float[] left_grip, float[] right_grip,
                      Map<String, float[]> contacts, String key, float bell, float bore, float mouth_rim,
                      float[] mouth, float[][] valves, Map<Integer, List<String>> fingerings) {}
    public record StringSpec(String name, float[] nut, float[] saddle, float[] tuner, float diameter) {}
    record Motion(String control, String parent, float[] pivot, float[] axis, float degrees, float zero, float[] translation) {}
    record Part(String name, Motion motion, List<float[]> quads) {}
    record Geometry(int version, String units, Rig rig, List<Part> parts) {}

    private record Joint(InstrumentAnimation.Control control, Motion motion, Joint parent) {
        void apply(PoseStack pose, InstrumentAnimation.Frame frame) {
            if (parent != null) parent.apply(pose, frame);
            if (control == null) return;
            float amount = frame.control(control) - motion.zero;
            if (amount == 0) return;
            if (motion.translation != null) {
                float[] d = motion.translation;
                pose.translate(d[0] * amount, d[1] * amount, d[2] * amount);
            } else {
                float[] p = motion.pivot, a = motion.axis;
                pose.translate(p[0], p[1], p[2]);
                pose.mulPose(new Quaternionf().fromAxisAngleDeg(a[0], a[1], a[2], motion.degrees * amount));
                pose.translate(-p[0], -p[1], -p[2]);
            }
        }
    }
    private record Surface(Joint joint, List<float[]> quads) {}
    private record Model(Rig rig, List<Surface> surfaces) {}
    private static Map<InstrumentKind, Model> models = Map.of();

    private InstrumentModel() {}

    static ResourceLocation meshPath(InstrumentKind kind) {
        return CreateMediaSystems.id("models/item/instruments/" + kind.model + ".mesh.json");
    }

    static Geometry read(Reader reader) throws IOException {
        var geometry = new Gson().fromJson(reader, Geometry.class);
        if (geometry == null || geometry.version != 1 || !"metres".equals(geometry.units)
                || geometry.rig == null || geometry.parts == null) {
            throw new IOException("Unknown instrument mesh format");
        }
        return geometry;
    }

    static void reload(ResourceManager resources) {
        var loaded = new EnumMap<InstrumentKind, Model>(InstrumentKind.class);
        for (InstrumentKind kind : InstrumentKind.values()) {
            var path = meshPath(kind);
            try (var reader = resources.getResourceOrThrow(path).openAsReader()) {
                Geometry geometry = read(reader);
                Map<String, Part> named = new HashMap<>();
                for (Part part : geometry.parts) {
                    if (named.put(part.name, part) != null) throw new IOException("Repeated part " + part.name);
                }
                var surfaces = geometry.parts.stream().map(part -> new Surface(
                        joint(part, named, 0), part.quads)).toList();
                loaded.put(kind, new Model(geometry.rig, surfaces));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Cannot load " + path, e);
            }
        }
        models = Map.copyOf(loaded);
    }

    private static Joint joint(Part part, Map<String, Part> named, int depth) {
        if (part == null || depth > named.size()) throw new IllegalArgumentException("Invalid parent chain");
        var motion = part.motion;
        if (motion == null) return null;
        var control = InstrumentAnimation.Control.named(motion.control);
        if (motion.control != null && control == null) throw new IllegalArgumentException("Unknown control " + motion.control);
        return new Joint(control, motion, motion.parent == null ? null : joint(named.get(motion.parent), named, depth + 1));
    }

    public static Rig rig(InstrumentKind kind) {
        var model = models.get(kind);
        return model == null ? null : model.rig;
    }

    public static ResourceLocation texture(String finish) {
        return CreateMediaSystems.id("textures/item/instruments/" + finish + ".png");
    }

    public static void render(PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                              InstrumentKind kind, String finish, InstrumentAnimation.Frame frame) {
        Model model = models.get(kind);
        if (model == null) return;
        var out = buffers.getBuffer(RenderType.entityCutoutNoCull(texture(finish)));
        for (var surface : model.surfaces) {
            if (surface.joint != null) {
                pose.pushPose();
                surface.joint.apply(pose, frame);
            }
            for (float[] q : surface.quads) {
                for (int i = 3; i < 23; i += 5) {
                    Mesh.vertex(pose, out, q[i], q[i+1], q[i+2], q[i+3], q[i+4], 0xFFFFFF, q[0], q[1], q[2], light, overlay);
                }
            }
            if (surface.joint != null) pose.popPose();
        }
        if (kind == InstrumentKind.STRATOCASTER) GuitarStrings.render(pose, out, light, overlay, model.rig, frame);
    }

    /** Centers the instrument for the GUI, dropped items, and the ordinary one-handed item pose. */
    static void centerItem(PoseStack pose, InstrumentKind kind) {
        switch (kind) {
            case STRATOCASTER -> pose.translate(-.337, 0, -.006);
            case FLUTE -> pose.translate(-.220, -.003, -.003);
            case TRUMPET -> {
                pose.mulPose(Axis.YP.rotationDegrees(90));
                pose.translate(0, .012, -.270);
            }
        }
    }

    static Vector3f point(float[] xyz) { return new Vector3f(xyz[0], xyz[1], xyz[2]); }
}
