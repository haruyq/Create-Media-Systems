package org.haruyq.createmediasystems.broadcast.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import org.haruyq.createmediasystems.broadcast.BroadcastProfile;
import org.haruyq.createmediasystems.client.mesh.Mesh;

final class BroadcastTowerMesh {
    private static final int STEEL = 0x9DA4A9, DARK_STEEL = 0x5F686D, BRACKET = 0x777F84;
    private static final int SIDES = 20;
    private static final float[] COS = new float[SIDES + 1], SIN = new float[SIDES + 1];

    static {
        for (int i = 0; i < SIDES; i++) {
            double a = i * 2 * Math.PI / SIDES;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
        COS[SIDES] = COS[0];
        SIN[SIDES] = SIN[0];
    }

    private BroadcastTowerMesh() {}

    static void draw(BroadcastProfile profile, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        VertexConsumer out = buffers.getBuffer(Mesh.solid());
        pose.pushPose();
        pose.translate(.5, 0, .5);
        box(pose, out, light, -.09f, 0, -.09f, .09f, 1.13f, .09f, STEEL);
        box(pose, out, light, -.115f, .025f, -.115f, .115f, .115f, .115f, BRACKET);
        box(pose, out, light, -.104f, 1.13f, -.104f, .104f, 1.161f, .104f, DARK_STEEL);
        box(pose, out, light, -.021f, 1.161f, -.021f, .021f, 1.37f, .021f, STEEL);
        box(pose, out, light, -.033f, 1.37f, -.033f, .033f, 1.405f, .033f, DARK_STEEL);
        for (int i = 0; i < 4; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(90 * i));
            if (profile == BroadcastProfile.ARRAY) column(pose, out, light, overlay);
            else horn(pose, out, light, profile == BroadcastProfile.DIGITAL_HORN, i);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void horn(PoseStack p, VertexConsumer v, int light, boolean digital, int index) {
        float y = digital ? .70f : index % 2 == 0 ? .72f : .53f;
        box(p, v, light, -.078f, y - .14f, .091f, .078f, y - .068f, .32f, BRACKET);
        box(p, v, light, -.065f, y - .134f, .318f, .065f, y - .025f, .355f, DARK_STEEL);
        p.translate(0, y, .17);
        p.mulPose(Axis.XP.rotationDegrees(7));
        int body = digital ? 0xD2D6D9 : 0xAAA99D;
        int inside = digital ? 0xB5C0C7 : 0x85877F;
        int edge = digital ? 0xDDE3E6 : 0xC2C0B0;
        int boot = digital ? 0x414E58 : 0x646760;
        sleeve(p, v, light, .091f, .091f, 0, .145f, boot, false);
        Mesh.ring(p, v, 0, 0, 0, .091f, 0, SIDES, -1, boot, light);
        float[] r = {.091f, .113f, .164f, .237f, .292f};
        float[] z = {.145f, .27f, .402f, .54f, .635f};
        for (int i = 0; i + 1 < r.length; i++) {
            sleeve(p, v, light, r[i], r[i + 1], z[i], z[i + 1], body, false);
            sleeve(p, v, light, r[i] - .013f, r[i + 1] - .013f, z[i], z[i + 1], inside, true);
        }
        sleeve(p, v, light, .292f, .295f, .635f, .653f, edge, false);
        sleeve(p, v, light, .279f, .280f, .635f, .653f, edge, true);
        Mesh.ring(p, v, 0, 0, .653f, .295f, .280f, SIDES, 1, edge, light);
        Mesh.ring(p, v, 0, 0, .145f, .078f, 0, SIDES, 1, DARK_STEEL, light);
        sleeve(p, v, light, .049f, .047f, .146f, .41f, body, false);
        Mesh.ring(p, v, 0, 0, .41f, .047f, 0, SIDES, 1, boot, light);
    }

    private static void sleeve(PoseStack p, VertexConsumer v, int light, float r0, float r1, float z0, float z1,
            int color, boolean inner) {
        float slope = (r0 - r1) / (z1 - z0);
        float scale = (inner ? -1 : 1) / (float) Math.sqrt(1 + slope * slope);
        for (int i = 0; i < SIDES; i++) {
            float a = COS[i], b = SIN[i], c = COS[i + 1], d = SIN[i + 1];
            Mesh.quad(p, v, r0 * a, r0 * b, z0, r0 * c, r0 * d, z0, r1 * c, r1 * d, z1, r1 * a, r1 * b, z1,
                    (a + c) * .5f * scale, (b + d) * .5f * scale, slope * scale, color, light);
        }
    }

    private static void column(PoseStack p, VertexConsumer v, int light, int overlay) {
        box(p, v, light, -.064f, .27f, .09f, .064f, .335f, .422f, BRACKET);
        box(p, v, light, -.064f, .905f, .09f, .064f, .97f, .422f, BRACKET);
        box(p, v, light, -.154f, .196f, .35f, .154f, 1.097f, .606f, 0x414B54);
        box(p, v, light, -.16f, .173f, .347f, .16f, .196f, .613f, 0x242A30);
        box(p, v, light, -.16f, 1.097f, .347f, .16f, 1.12f, .613f, 0x6D767C);
        box(p, v, light, -.147f, .20f, .607f, .147f, 1.092f, .633f, 0x171C22);
        for (int i = 0; i < 4; i++) {
            float y = .244f + i * .207f;
            Mesh.quad(p, v, -.12f, y + .174f, .642f, .12f, y + .174f, .642f, .12f, y, .642f, -.12f, y, .642f,
                    0, 0, 1, Mesh.Tile.VENTS, light, overlay);
            Mesh.ring(p, v, 0, y + .087f, .652f, .072f, .063f, 12, 1, 0x313A42, light);
        }
        for (int i = 0; i < 3; i++) {
            float z = .408f + .052f * i;
            box(p, v, light, .155f, .225f, z, .162f, 1.07f, z + .012f, 0x59636B);
            box(p, v, light, -.162f, .225f, z, -.155f, 1.07f, z + .012f, 0x59636B);
        }
        box(p, v, light, -.087f, .21f, .634f, -.012f, .224f, .638f, 0xB8C4CB);
    }

    private static void box(PoseStack p, VertexConsumer v, int light,
            float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        Mesh.box(p, v, x0, y0, z0, x1, y1, z1, color, light);
    }
}
