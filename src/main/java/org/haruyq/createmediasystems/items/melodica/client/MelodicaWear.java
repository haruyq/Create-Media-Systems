package org.haruyq.createmediasystems.items.melodica.client;

import net.minecraft.client.model.HumanoidModel;

import org.haruyq.createmediasystems.client.Worn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * How a melodica is carried and played. Carried, it is held against the outside of the left hand and swings with that arm.
 * Played, its mouthpiece goes to the lips and it follows the head, the left hand under its strap and the right on its
 * keys. {@code play} is the way between, 0 carried to 1 played; move it at 1 / {@link #RAISE} a second.
 */
public final class MelodicaWear {
    /** Seconds it takes to raise to the lips. */
    public static final float RAISE = .28f;

    /** Against the outside of the left hand, in that arm's upright frame: keys outward, mouthpiece up. */
    private static final Matrix4f CARRIED = Worn.stand(new Vector3f(-.214f, -.500f, 0), new Vector3f(0, -1, 0),
            new Vector3f(-1, 0, 0), Worn.METRE);
    /** At the lips, in the head's upright frame: pointing down and away, keys turned up to the right hand. */
    private static final Matrix4f PLAYED = lips(new Vector3f(0, .100f, -.262f), new Vector3f(.40f, -.18f, -.90f),
            new Vector3f(-.12f, .94f, .32f));

    private MelodicaWear() {}

    private static Matrix4f lips(Vector3f mouth, Vector3f along, Vector3f facing) {
        Matrix4f turned = Worn.stand(new Vector3f(), along, facing, Worn.METRE);
        Vector3f tip = turned.transformPosition(new Vector3f(MelodicaMesh.MOUTH_X, MelodicaMesh.MOUTH_Y, MelodicaMesh.MOUTH_Z));
        return new Matrix4f().translate(mouth.sub(tip)).mul(turned);
    }

    /** Where to draw {@link MelodicaMesh} in a layer of {@code model}. Ask after the arms are posed. */
    public static Matrix4f placement(HumanoidModel<?> model, float play) {
        return placement(model, play, null);
    }

    public static Matrix4f placement(HumanoidModel<?> model, float play, Vector3f eye) {
        Matrix4f carried = Worn.frame(model.leftArm).mul(CARRIED);
        if (play <= 0) return carried;
        Matrix4f played = Worn.frame(model.head).mul(PLAYED);
        if (eye != null) {
            var lips = Worn.frame(model.head).transformDirection(new Vector3f(0, -.093f, -.090f)).add(eye);
            var tip = played.transformPosition(new Vector3f(MelodicaMesh.MOUTH_X, MelodicaMesh.MOUTH_Y, MelodicaMesh.MOUTH_Z));
            played.translateLocal(lips.sub(tip));
        }
        return Worn.blend(carried, played, Worn.ease(play));
    }

    /**
     * Poses the arms towards where the played melodica is.
     *
     * @param keyX  where along the keyboard the right hand is, as {@link MelodicaMesh#keyX}
     * @param press how far the hand has followed a key down, 0 to 1
     */
    public static void arms(HumanoidModel<?> model, float play, float keyX, float press) {
        float hold = Worn.ease(play);
        if (hold <= 0) return;
        Matrix4f played = Worn.frame(model.head).mul(PLAYED);
        Worn.reach(model.leftArm, played.transformPosition(new Vector3f(MelodicaMesh.HOLD_X, MelodicaMesh.HOLD_Y - .006f,
                MelodicaMesh.HOLD_Z + .050f)), hold);
        Worn.reach(model.rightArm, played.transformPosition(new Vector3f(keyX, MelodicaMesh.KEY_FRONT + .010f,
                MelodicaMesh.KEY_TOP - .030f + .008f * press)), hold);
    }
}
