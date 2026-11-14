package org.haruyq.createmediasystems.items.keytar.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

import org.haruyq.createmediasystems.client.Worn;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.mesh.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * How the shoulder keyboard hangs on its wearer. The strap is a loop over the left shoulder and under the right arm,
 * and the instrument slides round it: across the back with its neck down by the right hip when it is put away, past
 * the hip, and up across the belly with its neck to the left when it is played. {@code ready} is that slide, 0 on the
 * back to 1 in front; move it at 1 / {@link #SWING} a second.
 */
public final class KeytarWear {
    /** Seconds the swing from the back to the front takes. */
    public static final float SWING = .55f;

    // In the torso's upright frame (Worn.frame of the body): x to the wearer's right, y up, the chest at -z.
    private static final Matrix4f PLAYED = new Matrix4f().translate(.110f, -.520f, -.320f).rotateY(-24 * Mth.DEG_TO_RAD)
            .rotateZ(-22 * Mth.DEG_TO_RAD).rotateX(42 * Mth.DEG_TO_RAD).scale(Worn.METRE);
    private static final Matrix4f AT_HIP = Worn.stand(new Vector3f(.425f, -.560f, -.040f), new Vector3f(0, .42f, .91f),
            new Vector3f(1, .15f, 0), Worn.METRE);
    private static final Matrix4f STOWED = Worn.stand(new Vector3f(.060f, -.450f, .168f), new Vector3f(-.55f, .83f, 0),
            new Vector3f(0, 0, 1), Worn.METRE);
    /** The strap's loop: its middle, the way from there up to the top of the left shoulder, and how far it stands off. */
    private static final Vector3f LOOP = new Vector3f(.035f, -.300f, 0), UP = new Vector3f(-.592f, .806f, 0);
    private static final float REACH = .400f, DEPTH = .152f;

    private KeytarWear() {}

    /** Where the keytar is in the torso's frame. {@code seconds} keeps a played one swaying. */
    public static Matrix4f hang(float ready, float seconds) {
        float slide = Worn.ease(ready);
        Matrix4f at = slide < .5f ? Worn.blend(STOWED, AT_HIP, slide * 2) : Worn.blend(AT_HIP, PLAYED, slide * 2 - 1);
        float played = Worn.ease((ready - .8f) / .2f);
        return played > 0 ? at.rotateZ(played * 1.6f * Mth.DEG_TO_RAD * Mth.sin(seconds * 2.2f)) : at;
    }

    /** Where to draw {@link KeytarMesh} in a layer of {@code model}. */
    public static Matrix4f placement(HumanoidModel<?> model, float ready, float seconds) {
        return Worn.frame(model.body).mul(hang(ready, seconds));
    }

    /**
     * Poses the arms: the left hand on the neck and the right over the keys once it is in front, and the right arm held
     * clear while it swings past the hip.
     *
     * @param keyX  where along the keyboard the right hand is, as {@link KeytarMesh#keyX}
     * @param press how far the hand has followed a key down, 0 to 1
     */
    public static void arms(HumanoidModel<?> model, float ready, float seconds, float keyX, float press) {
        if (ready <= 0) return;
        float slide = Worn.ease(ready), hold = Worn.ease((ready - .55f) / .45f);
        model.rightArm.zRot += .95f * Mth.sin(slide * Mth.PI) * (1 - hold);
        if (hold <= 0) return;
        Matrix4f placed = placement(model, ready, seconds);
        Worn.reach(model.rightArm, placed.transformPosition(new Vector3f(Mth.clamp(keyX, KeytarMesh.KEY_LEFT, KeytarMesh.KEY_RIGHT),
                KeytarMesh.KEY_FRONT + .034f, KeytarMesh.KEY_TOP - .020f + .012f * press)), hold);
        Worn.reach(model.leftArm, placed.transformPosition(new Vector3f(KeytarMesh.GRIP_X, KeytarMesh.GRIP_Y,
                KeytarMesh.GRIP_Z + .030f)), hold);
    }

    /** Draws the strap from the tail pin, round the loop, to the neck pin, in the space of a layer of {@code model}. */
    public static void strap(PoseStack p, MultiBufferSource buffers, int light, HumanoidModel<?> model, float ready, float seconds) {
        float slide = Worn.ease(ready);
        Matrix4f hung = hang(ready, seconds), torso = Worn.frame(model.body);
        Vector3f tail = hung.transformPosition(new Vector3f(KeytarMesh.TAIL_PIN_X, KeytarMesh.TAIL_PIN_Y, KeytarMesh.PIN_Z));
        Vector3f neck = hung.transformPosition(new Vector3f(KeytarMesh.NECK_PIN_X, KeytarMesh.NECK_PIN_Y, KeytarMesh.PIN_Z));
        // Degrees round the loop: 0 on the left shoulder, 90 at the chest, 180 at the right hip, 270 on the back.
        float from = Mth.lerp(slide, 345, 165) + 14, to = Mth.lerp(slide, 550, 415) - 14;
        int steps = Math.max(2, Math.round((to - from) / 10));
        float[] path = new float[(steps + 3) * 3], out = new float[(steps + 3) * 3];
        Vector3f point = new Vector3f(), away = new Vector3f();
        for (int i = 0; i <= steps; i++) {
            float angle = Mth.lerp(i / (float) steps, from, to) * Mth.DEG_TO_RAD, across = Mth.sin(angle);
            // Squared off towards the chest and back, since a torso is a slab and not a barrel.
            float depth = -DEPTH * Math.signum(across) * (float) Math.pow(Math.abs(across), .3);
            point.set(UP).mul(REACH * Mth.cos(angle)).add(LOOP).add(0, 0, depth);
            away.set(point).sub(LOOP).normalize();
            put(path, out, i + 1, torso.transformPosition(point), torso.transformDirection(away));
        }
        put(path, out, 0, torso.transformPosition(tail), new Vector3f(out[3], out[4], out[5]));
        int last = steps + 2;
        put(path, out, last, torso.transformPosition(neck), new Vector3f(out[last * 3 - 3], out[last * 3 - 2], out[last * 3 - 1]));
        Shapes.ribbon(p, buffers.getBuffer(Mesh.solid()), path, out, .024f, 0x16181B, light);
    }

    private static void put(float[] path, float[] out, int index, Vector3f point, Vector3f away) {
        path[index * 3] = point.x;
        path[index * 3 + 1] = point.y;
        path[index * 3 + 2] = point.z;
        out[index * 3] = away.x;
        out[index * 3 + 1] = away.y;
        out[index * 3 + 2] = away.z;
    }
}
