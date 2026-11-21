package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** A two-segment arm using the player's wide or slim skin and sleeve UVs. */
final class InstrumentPlayerArms {
    record Limb(Vector3f shoulder, Vector3f elbow, Vector3f hand) {}

    private InstrumentPlayerArms() {}

    static void render(PoseStack pose, MultiBufferSource buffers, int light, ResourceLocation skin,
                       Vector3f leftGrip, Vector3f rightGrip, boolean picking, boolean slim, boolean leftSleeve,
                       boolean rightSleeve, ModelPart left, ModelPart right) {
        var out = buffers.getBuffer(RenderType.entityCutoutNoCull(skin));
        drawArm(pose,out,light,picking,leftGrip,shoulder(left,true,slim),slim,leftSleeve,true);
        drawArm(pose,out,light,picking,rightGrip,shoulder(right,false,slim),slim,rightSleeve,false);
    }

    private static Vector3f shoulder(ModelPart part, boolean left, boolean slim) {
        float sign = left ? 1 : -1;
        if (part == null) return new Vector3f(sign * (slim ? .344f : .375f), .0625f, 0);
        return new Vector3f(part.x/16 + sign*(slim ? .03125f : .0625f), (part.y-1.1f)/16, part.z/16);
    }

    private static void drawArm(PoseStack pose, VertexConsumer out, int light, boolean picking, Vector3fc grip,
                                Vector3f shoulder, boolean slim, boolean sleeve, boolean left) {
        // Elbows drop and open outward. The guitar's picking arm goes over the body.
        float forward = picking && !left ? -.70f : .28f;
        var limb = solve(shoulder, grip, new Vector3f(left ? .24f : -.36f, 1.15f, forward));
        int width = slim ? 3 : 4;
        int u = left ? 32 : 40, v = left ? 48 : 16;
        skin(pose, out, light, limb, width, u, v, 0);
        if (sleeve) skin(pose, out, light, limb, width, left ? 48 : 40, left ? 48 : 32, .015625f);
    }

    static Limb solve(Vector3fc shoulder, Vector3fc grip, Vector3fc hint) {
        float upper = .354f, forearm = .369f;
        var direction = new Vector3f(grip).sub(shoulder);
        float distance = direction.length();
        if (distance < .0001f) direction.set(0,1,0);
        else direction.div(distance);
        distance = Math.clamp(distance, .07f, upper + forearm - .0002f);
        var bend = new Vector3f(hint).fma(-hint.dot(direction), direction);
        if (bend.lengthSquared() <= .000001f) bend = direction.cross(0,0,1,new Vector3f());
        if (bend.lengthSquared() <= .000001f) bend.set(0,1,0);
        bend.normalize();
        float projected = (upper*upper - forearm*forearm + distance*distance) / (2*distance);
        float height = (float)Math.sqrt(Math.max(0, upper*upper - projected*projected));
        var elbow = new Vector3f(shoulder).fma(projected,direction).fma(height,bend);
        return new Limb(new Vector3f(shoulder), elbow, new Vector3f(shoulder).fma(distance,direction));
    }

    private static void skin(PoseStack pose, VertexConsumer out, int light, Limb arm, int pixels, int u, int v,
                             float thickness) {
        float width = pixels/32f + thickness, depth = .125f + thickness;
        Vector3f upper = new Vector3f(arm.elbow()).sub(arm.shoulder()).normalize();
        Vector3f fore = new Vector3f(arm.hand()).sub(arm.elbow()).normalize();
        Vector3f palm = new Vector3f(arm.hand()).fma(thickness, fore);
        Vector3f shoulder = new Vector3f(arm.shoulder()).fma(-thickness, upper);
        Vector3f across = upper.cross(fore,new Vector3f()).normalize();
        if (across.x<0) across.negate();
        var top = ring(shoulder,upper,across,width,depth);
        var elbow = ring(arm.elbow(),new Vector3f(upper).add(fore).normalize(),across,width*.80f,depth*.78f);
        var bottom = ring(palm,fore,across,width*.56f,depth*.52f);
        sides(pose,out,light,top,elbow,pixels,u,v+4,v+10);
        sides(pose,out,light,elbow,bottom,pixels,u,v+10,v+16);
        quad(pose,out,light,top[3],top[0],top[1],top[2],u+4,v,u+4+pixels,v+4);
        capHand(pose,out,light,bottom,pixels,u,v);
    }

    private static void sides(PoseStack pose, VertexConsumer out, int light, Vector3f[] top,
                              Vector3f[] bottom, int pixels, int u, int v0, int v1) {
        int[] starts = {u+4, u+4+pixels, u+8+pixels, u};
        int[] sizes = {pixels, 4, pixels, 4};
        for (int i = 0; i < 4; i++) {
            int j = (i+1)%4, left = starts[i], right = left + sizes[i];
            quad(pose,out,light,top[i],bottom[i],bottom[j],top[j],left,v0,right,v1);
        }
    }

    private static void capHand(PoseStack pose, VertexConsumer out, int light, Vector3f[] bottom, int pixels, int u, int v) {
        quad(pose,out,light,bottom[0],bottom[3],bottom[2],bottom[1],u+4+pixels,v,u+4+2*pixels,v+4);
    }

    private static Vector3f[] ring(Vector3fc center, Vector3fc axis, Vector3fc hint, float w, float d) {
        var u = new Vector3f(hint).fma(-axis.dot(hint), axis);
        if (u.lengthSquared() < .00001f) u.set(0,1,0).fma(-axis.y(),axis);
        u.normalize(w);
        var v = u.cross(axis, new Vector3f()).normalize(d);
        return new Vector3f[]{
            new Vector3f(center).sub(u).sub(v), new Vector3f(center).add(u).sub(v),
            new Vector3f(center).add(u).add(v), new Vector3f(center).sub(u).add(v)
        };
    }

    private static void quad(PoseStack pose, VertexConsumer out, int light, Vector3fc a, Vector3fc b, Vector3fc c,
                             Vector3fc d, int u0, int v0, int u1, int v1) {
        var n = new Vector3f(b).sub(a).cross(new Vector3f(d).sub(a)).normalize();
        vertex(pose,out,light,a,n,u0,v0);
        vertex(pose,out,light,b,n,u0,v1);
        vertex(pose,out,light,c,n,u1,v1);
        vertex(pose,out,light,d,n,u1,v0);
    }

    private static void vertex(PoseStack pose, VertexConsumer out, int light, Vector3fc point, Vector3fc n, int u, int v) {
        Mesh.vertex(pose,out,point.x(),point.y(),point.z(),u/64f,v/64f,0xFFFFFF,n.x(),n.y(),n.z(),
                light,OverlayTexture.NO_OVERLAY);
    }
}
