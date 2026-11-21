package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.haruyq.createmediasystems.items.instrument.InstrumentKind;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Mount and both wrist targets in Mojang's Y-down player-model space. */
final class InstrumentPlayerPose {
    record Mount(Matrix4f transform, Vector3f leftGrip, Vector3f rightGrip, Matrix4f body) {
        Vector3f at(float[] point) { return transform.transformPosition(InstrumentModel.point(point)); }
    }

    private static final float PI = (float)Math.PI;
    private static final float DEG = PI / 180;

    private InstrumentPlayerPose() {}

    static Mount create(InstrumentKind kind, InstrumentModel.Rig rig, InstrumentAnimation.Frame frame,
                        ModelPart bodyPart, ModelPart headPart) {
        return create(kind, rig, frame, bodyPart, headPart, null);
    }

    static Mount create(InstrumentKind kind, InstrumentModel.Rig rig, InstrumentAnimation.Frame frame,
                        ModelPart bodyPart, ModelPart headPart, Vector3f eye) {
        Matrix4f body = partMatrix(bodyPart);
        Matrix4f transform;
        Vector3f left, right;
        float playing = frame.playing();
        switch (kind) {
            case STRATOCASTER -> {
                // The neck projects past the left shoulder. Tip the upper bout toward the chest
                // so the player can sight along the strings from above.
                transform = new Matrix4f(body).translate(-.164f, .59f - .015f * playing, -.214f)
                        .rotateY(18 * DEG).rotateX((149 - 7 * playing) * DEG).rotateZ((10 + 9 * playing) * DEG);
                left = new Vector3f(GuitarStrings.fretX(rig, frame.fretHand()) - .009f, -.043f, -.009f);
                right = InstrumentModel.point(rig.pick_point()).add(-.012f, .037f * frame.stroke(), .014f);
            }
            case FLUTE -> {
                Vector3f rest = body.transformPosition(new Vector3f(.115f, .438f, -.270f));
                transform = wind(rest, headPart, eye, playing, .090f)
                        .rotateY((-12 - 42 * playing) * DEG)
                        .rotateZ((157 + 18 * playing) * DEG).rotateX(-.30f)
                        .translate(-rig.embouchure()[0], -rig.embouchure()[1], -rig.embouchure()[2]);
                // The wrist sits on the lip side of the key group; the palm covers the contact.
                left = InstrumentModel.point(rig.left_grip()).add(-.025f, 0, 0);
                right = InstrumentModel.point(rig.right_grip()).add(0, -.006f * frame.control(InstrumentAnimation.Control.RIGHT_MIDDLE), 0);
            }
            case TRUMPET -> {
                Vector3f rest = body.transformPosition(new Vector3f(0, .408f, -.254f));
                transform = wind(rest, headPart, eye, playing, .027f)
                        .rotateY(5 * playing * DEG)
                        .rotateX(PI + (30 - 23 * playing) * DEG)
                        .scale(InstrumentModel.TRUMPET_DISPLAY_SCALE)
                        .translate(-rig.mouth()[0], -rig.mouth()[1], -rig.mouth()[2]);
                left = InstrumentModel.point(rig.left_grip()).add(0, 0, .012f * frame.control(InstrumentAnimation.Control.THIRD_SLIDE));
                float valves = frame.control(InstrumentAnimation.Control.VALVE_1)
                        + frame.control(InstrumentAnimation.Control.VALVE_2) + frame.control(InstrumentAnimation.Control.VALVE_3);
                right = InstrumentModel.point(rig.right_grip()).add(0, -.0131f*valves/3, 0);
            }
            default -> throw new IllegalStateException("Unknown instrument");
        }
        return new Mount(transform, transform.transformPosition(left), transform.transformPosition(right), body);
    }

    private static Matrix4f partMatrix(ModelPart model) {
        if (model == null) return new Matrix4f();
        PoseStack pose = new PoseStack();
        model.translateAndRotate(pose);
        return pose.last().pose();
    }

    private static Matrix4f wind(Vector3f rest, ModelPart head, Vector3f eye, float raised, float lipDepth) {
        var rotation = new Quaternionf();
        partMatrix(head).getNormalizedRotation(rotation);
        // The first-person eye is the camera pivot, including when the player looks straight up or crouches.
        var lips = eye == null ? partMatrix(head).transformPosition(new Vector3f(0, -.095f, -.253f))
                : rotation.transform(new Vector3f(0, .093f, -lipDepth)).add(eye);
        return new Matrix4f().translation(rest.lerp(lips, raised))
                .rotate(new Quaternionf().slerp(rotation, raised));
    }
}
