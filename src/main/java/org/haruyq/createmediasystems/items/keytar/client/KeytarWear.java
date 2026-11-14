package org.haruyq.createmediasystems.items.keytar.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

import org.haruyq.createmediasystems.client.Worn;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.mesh.Shapes;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The AX-Edge slides around the wearer's right side between the two mounts supplied by its rig. */
public final class KeytarWear {
    public static final float SWING = .55f;
    private static final Matrix4f AT_HIP = Worn.stand(new Vector3f(-.43f, .50f, .015f),
            new Vector3f(0, -.52f, .85f), new Vector3f(1, 0, 0), Worn.METRE);
    private static final Vector3f GRIP = new Vector3f(.426f, .088f, .055f);

    private KeytarWear() {}

    static Matrix4f hang(KeytarMesh.Rig rig, float ready, float seconds) {
        float slide = Worn.ease(ready);
        Matrix4f at = slide < .5f ? Worn.blend(rig.back(), AT_HIP, slide * 2) : Worn.blend(AT_HIP, rig.front(), slide * 2 - 1);
        float played = Worn.ease((ready - .8f) / .2f);
        return at.rotateZ(played * 1.6f * Mth.DEG_TO_RAD * Mth.sin(seconds * 2.2f));
    }

    @Nullable public static Matrix4f placement(HumanoidModel<?> model, float ready, float seconds) {
        var rig = KeytarMesh.rig();
        return rig == null ? null : Worn.frame(model.body).rotateZ(-Mth.PI).mul(hang(rig, ready, seconds));
    }

    public static void arms(HumanoidModel<?> model, float ready, float seconds, float keyX, int note, float press) {
        if (ready <= 0) return;
        float slide = Worn.ease(ready), hold = Worn.ease((ready - .55f) / .45f);
        model.rightArm.zRot += .95f * Mth.sin(slide * Mth.PI) * (1 - hold);
        if (hold <= 0) return;
        var placed = placement(model, ready, seconds);
        if (placed == null) return;
        Worn.reach(model.rightArm, placed.transformPosition(KeytarMesh.touch(note, keyX, press)), hold);
        Worn.reach(model.leftArm, placed.transformPosition(GRIP, new Vector3f()), hold);
    }

    /** The strap stays attached to its pins throughout the swing, including between the authored mounts. */
    public static void strap(PoseStack pose, MultiBufferSource buffers, int light, HumanoidModel<?> model, float ready, float seconds) {
        var rig = KeytarMesh.rig();
        if (rig == null) return;
        var hung = hang(rig, ready, seconds);
        float slide = Worn.ease(ready), around = 1 - Math.abs(2 * slide - 1);
        var front = rig.frontStrap();
        var back = rig.backStrap();
        int last = front.size() - 1;
        float[] path = new float[front.size() * 3], out = new float[path.length];
        var point = new Vector3f();
        for (int i = 0; i <= last; i++) {
            if (i == 0) hung.transformPosition(rig.tailPin(), point);
            else if (i == last) hung.transformPosition(rig.neckPin(), point);
            else {
                float[] a = back.get(i), b = front.get(i);
                point.set(Mth.lerp(slide, a[0], b[0]), Mth.lerp(slide, a[1], b[1]), Mth.lerp(slide, a[2], b[2]));
                if (i == 1 || i == last - 1) point.x = Mth.lerp(around, point.x, i == 1 ? -.32f : .30f);
                else point.y -= .032f * around;
            }
            put(path, i, point);
            put(out, i, point.sub(0, .28f, 0, new Vector3f()).normalize());
        }
        pose.pushPose();
        model.body.translateAndRotate(pose);
        Shapes.ribbon(pose, buffers.getBuffer(Mesh.solid()), path, out, .025f, 0x17181B, light);
        pose.popPose();
    }

    private static void put(float[] triples, int index, Vector3f point) {
        int offset = index * 3;
        triples[offset] = point.x;
        triples[offset + 1] = point.y;
        triples[offset + 2] = point.z;
    }
}
