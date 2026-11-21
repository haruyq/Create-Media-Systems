package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
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
        return create(kind, rig, frame, bodyPart, headPart, false);
    }

    static Mount firstPerson(InstrumentKind kind, InstrumentModel.Rig rig, InstrumentAnimation.Frame frame) {
        return create(kind, rig, frame, null, null, true);
    }

    private static Mount create(InstrumentKind kind, InstrumentModel.Rig rig, InstrumentAnimation.Frame frame,
                                ModelPart bodyPart, ModelPart headPart, boolean firstPerson) {
        Matrix4f body = partMatrix(bodyPart);
        Matrix4f transform;
        Vector3f left, right;
        float playing = frame.playing();
        switch (kind) {
            case STRATOCASTER -> {
                transform = new Matrix4f(body).translate(-.164f, .605f - .025f * playing, -.214f)
                        .rotateX(firstPerson ? -.59f : PI).rotateZ((10 + 9 * playing) * DEG);
                left = new Vector3f(GuitarStrings.fretX(rig, frame.fretHand()) - .009f, -.065f, -.014f);
                right = InstrumentModel.point(rig.pick_point()).add(-.012f, .056f * frame.stroke(), .014f);
            }
            case FLUTE -> {
                Vector3f rest = body.transformPosition(new Vector3f(.115f, .438f, -.270f));
                Vector3f mouth = partMatrix(headPart).transformPosition(new Vector3f(0, -.095f, -.253f));
                var position = rest.lerp(mouth, playing);
                transform = new Matrix4f().translation(position)
                        .rotateY(headTurn(headPart) * playing)
                        .rotateX(headTilt(headPart) * playing)
                        .rotateZ((157 + 23 * playing) * DEG)
                        .rotateX(firstPerson ? .62f : -.30f)
                        .translate(-rig.embouchure()[0], -rig.embouchure()[1], -rig.embouchure()[2]);
                left = InstrumentModel.point(rig.left_grip());
                right = InstrumentModel.point(rig.right_grip()).add(0, -.006f * frame.control(InstrumentAnimation.Control.RIGHT_MIDDLE), 0);
                if (firstPerson) {
                    left.add(0,.042f,-.014f);
                    right.add(0,.042f,-.014f);
                }
            }
            case TRUMPET -> {
                Vector3f rest = body.transformPosition(new Vector3f(0, .408f, -.254f));
                Vector3f mouth = partMatrix(headPart).transformPosition(new Vector3f(0, -.095f, -.254f));
                var position = rest.lerp(mouth, playing);
                transform = new Matrix4f().translation(position)
                        .rotateY(headTurn(headPart) * playing + (firstPerson ? .63f : 0))
                        .rotateX(headTilt(headPart) * playing + PI + .51f*(1-playing) + (firstPerson ? .39f : 0))
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

    private static float headTurn(ModelPart head) {
        return head == null ? 0 : Mth.clamp(head.yRot, -.66f, .66f);
    }

    private static float headTilt(ModelPart head) {
        return head == null ? 0 : Mth.clamp(head.xRot, -.61f, .61f);
    }
}
