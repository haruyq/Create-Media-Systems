package org.haruyq.createmediasystems.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Hanging something on a player's model rather than in their hand. Everything here is in the space a render layer
 * draws in: the model's own, one unit to sixteen of its pixels, y down. An instrument is authored in metres with y up,
 * so each part gets an upright {@link #frame}: x to the wearer's right, y up, the front at -z.
 */
public final class Worn {
    /** One metre, in model units: the player renderer draws the model at 15/16 of its size. */
    public static final float METRE = 1 / .9375f;

    private Worn() {}

    /** The upright frame of {@code part}, from the origin of the model. */
    public static Matrix4f frame(ModelPart part) {
        return new Matrix4f().translate(part.x / 16, part.y / 16, part.z / 16)
                .rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot)).rotateZ(Mth.PI);
    }

    /** Draws what follows through {@code placement}, which may turn, move and evenly scale but not shear. */
    public static void place(PoseStack pose, Matrix4f placement) {
        Vector3f at = placement.getTranslation(new Vector3f()), size = placement.getScale(new Vector3f());
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(placement.getUnnormalizedRotation(new Quaternionf()));
        pose.scale(size.x, size.x, size.x);
    }

    /** The placement {@code amount} of the way from {@code from} to {@code to}, turning the short way round. */
    public static Matrix4f blend(Matrix4f from, Matrix4f to, float amount) {
        Vector3f at = from.getTranslation(new Vector3f()).lerp(to.getTranslation(new Vector3f()), amount);
        Quaternionf turn = from.getUnnormalizedRotation(new Quaternionf())
                .slerp(to.getUnnormalizedRotation(new Quaternionf()), amount);
        float size = Mth.lerp(amount, from.getScale(new Vector3f()).x, to.getScale(new Vector3f()).x);
        return new Matrix4f().translate(at).rotate(turn).scale(size);
    }

    /**
     * Points {@code arm} at {@code target}, {@code weight} of the way from however it is posed now. An arm is one
     * straight piece ten pixels long from its pivot, so the hand lands on the target only if that is how far away it is.
     */
    public static void reach(ModelPart arm, Vector3f target, float weight) {
        if (weight <= 0) return;
        float x = target.x - arm.x / 16, y = target.y - arm.y / 16, z = target.z - arm.z / 16;
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-5f) return;
        // Turned by z, then y, then x from hanging straight down (0, 1, 0), an arm points along (-cx sz, cx cz, sx).
        float lift = (float) Math.asin(Mth.clamp(z / length, -1, 1)), swing = (float) Math.atan2(-x, y);
        arm.xRot = Mth.rotLerp(weight, arm.xRot * Mth.RAD_TO_DEG, lift * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
        arm.yRot = Mth.lerp(weight, arm.yRot, 0);
        arm.zRot = Mth.rotLerp(weight, arm.zRot * Mth.RAD_TO_DEG, swing * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
    }

    /**
     * A placement that stands something at {@code at} with its x axis along {@code along} and its -z face turned as
     * nearly towards {@code facing} as that allows, drawn {@code size} times its own size.
     */
    public static Matrix4f stand(Vector3f at, Vector3f along, Vector3f facing, float size) {
        Vector3f x = new Vector3f(along).normalize();
        Vector3f y = new Vector3f(facing).negate().cross(x).normalize();
        Vector3f z = new Vector3f(x).cross(y);
        return new Matrix4f(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, at.x, at.y, at.z, 1).scale(size);
    }

    /** Eases 0..1 in and out. */
    public static float ease(float amount) {
        float t = Mth.clamp(amount, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
