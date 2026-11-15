package org.haruyq.createmediasystems.blocks.steelpan.client;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanLayout;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class SteelpanModel {
    public static final ResourceLocation GEOMETRY = CreateMediaSystems.id("models/block/steelpan/steelpan.mesh.json");
    public static final ResourceLocation TEXTURE = CreateMediaSystems.id("textures/block/steelpan/low_c_silver.png");
    public record Quad(float[][] vertices, float[] normal) {}
    public record Part(String name, String assembly, String motion, int midi, int hand, float[] pivot, List<Quad> quads) {}
    public record Geometry(int version, String units, List<Part> parts) {}
    private static Geometry geometry;
    private static final SteelpanState REST = new SteelpanState();

    private SteelpanModel() {}

    static void reload(ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(GEOMETRY).openAsReader()) {
            var data = new Gson().fromJson(reader, Geometry.class);
            if (data.version() != 1 || !"metres".equals(data.units())
                    || data.parts().stream().filter(p -> "note".equals(p.motion())).count() != 29) {
                throw new IOException("Incompatible steelpan mesh");
            }
            geometry = data;
        } catch (IOException | RuntimeException failure) { throw new IllegalStateException("Cannot load " + GEOMETRY, failure); }
    }

    public static void panTransform(PoseStack pose, SteelpanState state, long now) {
        pose.translate(0, SteelpanLayout.DATA.rim_height(), 0);
        pose.mulPose(Axis.XP.rotationDegrees(SteelpanLayout.DATA.tilt() + state.sway(now)));
    }

    /** Origin at floor, centre of stand. All coordinates are metres, Y up, performer at +Z. */
    public static void render(PoseStack pose, MultiBufferSource buffers, int light, int overlay, SteelpanState state, long now) {
        if (geometry == null) return;
        if (state == null) state = REST;
        var out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for (var part : geometry.parts()) {
            pose.pushPose();
            if (!"stand".equals(part.assembly())) panTransform(pose, state, now);
            if ("mallet".equals(part.motion())) {
                Vector3f tip = malletTip(state, part.hand(), now);
                pose.translate(tip.x, tip.y, tip.z);
                pose.mulPose(malletRotation(state, part.hand(), now));
            }
            float vibration = "note".equals(part.motion()) ? state.vibration(part.midi(), now) : 0;
            for (var quad : part.quads()) {
                var n = quad.normal();
                for (var vertex : quad.vertices()) {
                    // Flex the field centre; the grooved perimeter's weight is exactly zero.
                    Mesh.vertex(pose, out, vertex[0], vertex[1] + vibration*vertex[5], vertex[2], vertex[3], vertex[4],
                            0xFFFFFF, n[0], n[1], n[2], light, overlay);
                }
            }
            pose.popPose();
        }
    }

    public static Vector3f malletTip(SteelpanState state, int hand, long now) {
        var m = state.mallet(hand, now);
        var from = SteelpanLayout.note(m.from()).center();
        var to = SteelpanLayout.note(m.to()).center();
        float x = from[0] + (to[0]-from[0])*m.progress();
        float z = from[2] + (to[2]-from[2])*m.progress();
        float y = SteelpanLayout.bowl(x,z) + .0028f + SteelpanLayout.DATA.mallet_radius() + m.lift();
        Vector3f rest = new Vector3f(hand == 0 ? -.045f : .045f, 0, .19f);
        rest.y = SteelpanLayout.bowl(rest.x,rest.z) + .011f;
        return rest.lerp(new Vector3f(x,y,z), m.activity());
    }

    public static Quaternionf malletRotation(SteelpanState state, int hand, long now) {
        float activity = state.mallet(hand, now).activity();
        Vector3f tip = malletTip(state, hand, now);
        // A small natural grip translation follows distant fields; shafts never stretch to reach them.
        Vector3f direction = new Vector3f((hand == 0 ? -.09f : .09f)-tip.x, .11f-tip.y, .29f-tip.z).normalize();
        Vector3f parked = new Vector3f(0, (float)Math.cos(Math.toRadians(40)), (float)Math.sin(Math.toRadians(40)));
        parked.lerp(direction, activity).normalize();
        return new Quaternionf().rotationTo(new Vector3f(0,1,0), parked);
    }
}
