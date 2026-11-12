package org.haruyq.createmediasystems.piano.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.piano.PianoState;

import static org.haruyq.createmediasystems.piano.PianoEvent.*;

final class PianoMesh {
    private static final float HINGE = .115f, LID_Y = 1.006f;
    private static final float FRONT = .595f, TAIL = 2.827f, CENTER = .91f;
    private static final int EBONY = 0x1D1C21, EDGE = 0x3B3A42, INSET = 0x0E0D10;
    private static final int BRASS = 0xBF9450, GOLD = 0xDDB972, FELT = 0x701C32;
    private static final int SECTIONS = 56;
    // Keyboard at z=0, straight bass rim on the left, bentside and rounded tail on the right.
    private static final float[][] PROFILE = {
        {FRONT, .10f, 1.72f}, {.80f, .10f, 1.72f}, {1.01f, .10f, 1.707f},
        {1.20f, .10f, 1.648f}, {1.40f, .10f, 1.512f}, {1.61f, .103f, 1.323f},
        {1.82f, .111f, 1.157f}, {2.03f, .132f, 1.077f}, {2.24f, .168f, 1.032f},
        {2.44f, .231f, .973f}, {2.60f, .318f, .887f}, {2.73f, .427f, .784f},
        {2.798f, .507f, .708f}, {TAIL, .597f, .623f}
    };
    private static final float[] LEFT = new float[SECTIONS + 1], RIGHT = new float[SECTIONS + 1];

    static {
        int j = 0;
        for (int i = 0; i <= SECTIONS; i++) {
            float z = Mth.lerp(i / (float) SECTIONS, FRONT, TAIL);
            while (j < PROFILE.length - 2 && z > PROFILE[j + 1][0]) j++;
            LEFT[i] = interpolate(j, z, 1);
            RIGHT[i] = interpolate(j, z, 2);
        }
    }

    private final PoseStack pose;
    private final VertexConsumer mesh;
    private final int light;

    PianoMesh(PoseStack pose, MultiBufferSource buffers, int light) {
        this.pose = pose;
        mesh = buffers.getBuffer(Mesh.solid());
        this.light = light;
    }

    void draw(PianoState state, float lidAngle) {
        casework();
        keyboard(state);
        leg(.265f, .430f, .082f);
        leg(1.555f, .430f, .082f);
        leg(.495f, 2.525f, .070f);
        lyre(state);
        if (lidAngle > .3f) interior();
        musicDesk(lidAngle / 43);
        lid(lidAngle);
    }

    private void casework() {
        // Four bands model the rounded overhang, vertical lacquer, and the inner wooden lining.
        band(.012f, .717f, 0, .743f, 0x111117);
        band(0, .743f, 0, .917f, EBONY);
        band(0, .917f, -.002f, .986f, 0x25242D);
        band(-.002f, .986f, .009f, .998f, EDGE);
        band(.009f, .998f, .061f, .995f, 0x2C2628);
        band(.061f, .995f, .064f, .817f, 0x534037);
        band(.013f, .997f, .018f, 1.007f, 0x151319);
        panel(.013f, .717f, -1, 0x161518);
        box(.104f, .695f, .085f, 1.717f, .778f, .597f, EBONY);
        box(.114f, .777f, .093f, 1.710f, .790f, .561f, 0x303039);
        box(.10f, .78f, .107f, .168f, .850f, .619f, EBONY);
        box(1.650f, .78f, .107f, 1.720f, .850f, .619f, EBONY);
        box(.113f, .848f, .133f, .169f, .857f, .575f, 0x373640);
        box(1.650f, .848f, .133f, 1.707f, .857f, .575f, 0x373640);
        box(.161f, .791f, .532f, 1.658f, .875f, .605f, INSET);
        box(.169f, .861f, .541f, 1.650f, .893f, .601f, EBONY);
        box(.176f, .797f, .515f, 1.644f, .810f, .540f, FELT);
        box(.841f, .872f, .539f, .979f, .876f, .541f, 0xAC8648);
    }

    private void keyboard(PianoState state) {
        int white = 0;
        float start = .174f, width = 1.470f / 52;
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            float dip = state.pressed(note) ? .011f : 0;
            float x = start + width * white;
            if (black(note)) {
                box(x - .0086f, .809f, .281f, x + .0086f, .853f - dip, .524f, 0x121218);
                box(x - .0073f, .853f - dip, .286f, x + .0073f, .857f - dip, .518f, 0x33313B);
            } else {
                float a = x + .00115f, b = x + width - .00115f;
                float shoulderLeft = isNote(note - 1) && black(note - 1) ? x + .0091f : a;
                float shoulderRight = isNote(note + 1) && black(note + 1) ? x + width - .0091f : b;
                box(a, .779f, .102f, b, .824f - dip, .281f, 0xC3BDAC);
                box(a, .824f - dip, .108f, b, .831f - dip, .281f, 0xF4EFE1);
                box(shoulderLeft, .781f, .281f, shoulderRight, .831f - dip, .526f, 0xEDE5D8);
                white++;
            }
        }
    }

    private void leg(float x, float z, float radius) {
        squareTaper(x, z, .697f, radius * 1.15f, .646f, radius, EDGE);
        squareTaper(x, z, .646f, radius, .143f, radius * .49f, EBONY);
        squareTaper(x, z, .143f, radius * .51f, .089f, radius * .43f, 0x776342);
        box(x - .043f, .049f, z - .035f, x + .043f, .099f, z + .035f, 0x9C783D);
        rod(x - .037f, .045f, z, x - .010f, .045f, z, .042f, .042f, 0x272631, 12);
        rod(x + .010f, .045f, z, x + .037f, .045f, z, .042f, .042f, 0x272631, 12);
        rod(x - .042f, .045f, z, x + .042f, .045f, z, .020f, .020f, GOLD, 12);
    }

    private void lyre(PianoState state) {
        box(.720f, .652f, .572f, 1.103f, .708f, .778f, EBONY);
        for (float x : new float[] {.771f, 1.053f}) {
            rod(x, .141f, .638f, x, .668f, .712f, .029f, .036f, EBONY, 4);
            rod(x, .150f, .670f, x, .670f, .791f, .008f, .008f, 0x85673D, 6);
        }
        box(.738f, .093f, .600f, 1.087f, .163f, .777f, EBONY);
        box(.741f, .158f, .603f, 1.084f, .174f, .773f, EDGE);
        pedal(.816f, state.pedal(SOFT));
        pedal(.912f, state.pedal(SOSTENUTO));
        pedal(1.008f, state.pedal(DAMPER));
    }

    private void pedal(float x, int value) {
        pose.pushPose();
        pose.translate(x, .111, .645);
        pose.mulPose(Axis.XN.rotationDegrees(value * 8f / 127));
        rod(0, 0, -.185f, 0, .017f, -.022f, .018f, .013f, BRASS, 8);
        box(-.030f, -.007f, -.207f, .030f, .008f, -.151f, BRASS);
        rod(0, .001f, -.209f, 0, .001f, -.196f, .021f, .029f, GOLD, 8);
        pose.popPose();
    }

    private void interior() {
        // Spruce strips, raised cast plate, bridges and a two-plane cross-strung scale.
        panel(.065f, .820f, 1, 0xB99162);
        band(.067f, .821f, .078f, .858f, 0x9E7137);
        band(.078f, .858f, .137f, .858f, 0xCBA45E);
        band(.137f, .858f, .145f, .840f, 0x83612F);
        box(.198f, .831f, .704f, 1.602f, .866f, .833f, 0xAD843F);
        box(.210f, .869f, .774f, 1.585f, .879f, .833f, 0x671E2C);
        rod(.218f, .862f, .908f, 1.551f, .862f, 1.002f, .021f, .024f, 0xBC9348, 4);
        rod(.350f, .857f, 1.233f, .556f, .857f, 2.643f, .021f, .027f, 0xC2A065, 4);
        rod(.422f, .866f, 2.310f, 1.574f, .866f, 1.119f, .012f, .015f, 0x775032, 6);
        rod(.645f, .889f, 2.519f, .974f, .889f, 1.720f, .012f, .015f, 0x78472A, 6);
        for (int i = 0; i < 68; i++) {
            float t = i / 67f;
            float x0 = Mth.lerp(t, .480f, 1.589f), x1 = Mth.lerp(t, .486f, 1.496f);
            float z1 = Mth.lerp(t, 2.514f, 1.090f);
            string(x0, .875f, .778f, x1, z1, .0023f, i % 3 == 0 ? 0xB6AC94 : 0xA59B87);
            if (i % 2 == 0) box(x0 - .009f, .878f, .950f, x0 + .009f, .899f, 1.026f, 0x3E2B28);
        }
        for (int i = 0; i < 20; i++) {
            float t = i / 19f;
            string(Mth.lerp(t, .211f, .461f), .913f, .764f, Mth.lerp(t, .664f, .954f),
                    Mth.lerp(t, 2.619f, 1.815f), .0038f, i % 2 == 0 ? 0xC3936D : 0xB17D5B);
        }
    }

    private void string(float x0, float y, float z0, float x1, float z1, float width, int color) {
        quad(x0 - width, y, z0, x0 + width, y, z0, x1 + width, y, z1, x1 - width, y, z1, 0, 1, 0, color);
        box(x0 - width, y - .006f, z0 - .006f, x0 + width, y + .015f, z0 + .006f, GOLD);
        box(x1 - width, y - .013f, z1 - .005f, x1 + width, y + .005f, z1 + .005f, 0xAC874B);
    }

    private void musicDesk(float opening) {
        box(.315f, .892f, .580f, 1.505f, .926f, .712f, EBONY);
        if (opening < .26f) return;
        pose.pushPose();
        pose.translate(.910, .913, .691);
        pose.mulPose(Axis.XP.rotationDegrees(88 - (opening - .26f) / .74f * 102));
        box(-.520f, 0, 0, .520f, .210f, .021f, 0x222129);
        box(-.465f, .210f, 0, .465f, .244f, .021f, EBONY);
        box(-.518f, .015f, -.063f, .518f, .034f, .025f, EDGE);
        box(-.488f, .033f, -.063f, .488f, .041f, -.055f, 0x514B48);
        for (float x : new float[] {-.427f, .427f}) {
            box(x - .016f, .039f, -.003f, x + .016f, .203f, .002f, 0x35333A);
        }
        pose.popPose();
    }

    private void lid(float angle) {
        pose.pushPose();
        pose.translate(HINGE, LID_Y, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(angle));
        pose.translate(-HINGE, -LID_Y, 0);
        band(.002f, LID_Y, -.014f, LID_Y + .013f, 0x27262D);
        band(-.014f, LID_Y + .013f, .006f, LID_Y + .032f, 0x44434C);
        panel(.006f, LID_Y + .032f, 1, 0x23232D);
        panel(.002f, LID_Y, -1, 0x19171E);
        box(.111f, 1.008f, .563f, 1.709f, 1.037f, .593f, EBONY);
        for (float z : new float[] {.844f, 1.327f, 1.887f}) {
            rod(.125f, 1.001f, z - .033f, .125f, 1.001f, z + .033f, .008f, .008f, BRASS, 8);
        }
        pose.popPose();
        if (angle > 1) {
            float a = angle * Mth.DEG_TO_RAD, arm = 1.449f - HINGE;
            float x = HINGE + Mth.cos(a) * arm, y = LID_Y + Mth.sin(a) * arm;
            rod(1.416f, .933f, 1.444f, x, y, 1.175f, .016f, .012f, EBONY, 8);
            rod(x, y - .036f, 1.181f, x, y + .003f, 1.175f, .019f, .017f, GOLD, 8);
        }
    }

    private static float interpolate(int i, float z, int axis) {
        float[] a = PROFILE[i], b = PROFILE[i + 1];
        float[] before = PROFILE[Math.max(0, i - 1)], after = PROFILE[Math.min(PROFILE.length - 1, i + 2)];
        float span = b[0] - a[0], t = (z - a[0]) / span;
        float ma = (b[axis] - before[axis]) / (b[0] - before[0]) * span;
        float mb = (after[axis] - a[axis]) / (after[0] - a[0]) * span;
        float y = Mth.lerp(t * t * (3 - 2 * t), a[axis], b[axis]) + t * (1 - t) * ((1 - t) * ma - t * mb);
        return Mth.clamp(y, Math.min(a[axis], b[axis]), Math.max(a[axis], b[axis]));
    }

    private static float atZ(int i, float inset) {
        return Mth.lerp(i / (float) SECTIONS, FRONT + inset, TAIL - inset);
    }

    private static float atX(float x, float inset) { return Mth.lerp(inset / .84f, x, CENTER); }

    private void panel(float inset, float y, int normal, int color) {
        for (int i = 0; i < SECTIONS; i++) {
            float a = atZ(i, inset), b = atZ(i + 1, inset);
            float tone = color == 0xB99162 ? .88f + (i * 29 % 13) * .01f : .93f + .14f * Mth.sin(i / 17f);
            quad(atX(LEFT[i], inset), y, a, atX(RIGHT[i], inset), y, a, atX(RIGHT[i + 1], inset), y, b,
                    atX(LEFT[i + 1], inset), y, b, 0, normal, 0, tint(color, tone));
        }
    }

    private void band(float inset0, float y0, float inset1, float y1, int color) {
        rimSide(LEFT, -1, inset0, y0, inset1, y1, color);
        rimSide(RIGHT, 1, inset0, y0, inset1, y1, color);
        for (int i : new int[] {0, SECTIONS}) {
            float z0 = atZ(i, inset0), z1 = atZ(i, inset1);
            quad(atX(LEFT[i], inset0), y0, z0, atX(RIGHT[i], inset0), y0, z0, atX(RIGHT[i], inset1), y1, z1,
                    atX(LEFT[i], inset1), y1, z1, 0, y1 <= y0 ? .65f : 0, i == 0 ? -1 : 1, color);
        }
    }

    private void rimSide(float[] side, int sign, float inset0, float y0, float inset1, float y1, int color) {
        for (int i = 0; i < SECTIONS; i++) {
            float a = atZ(i, inset0), b = atZ(i + 1, inset0);
            float c = atX(side[i], inset0), d = atX(side[i + 1], inset0);
            float inv = Mth.invSqrt((b - a) * (b - a) + (c - d) * (c - d));
            quad(c, y0, a, d, y0, b, atX(side[i + 1], inset1), y1, atZ(i + 1, inset1),
                    atX(side[i], inset1), y1, atZ(i, inset1), sign * (b - a) * inv,
                    y1 <= y0 ? .65f : 0, sign * (c - d) * inv, color);
        }
    }

    private void squareTaper(float x, float z, float y0, float a, float y1, float b, int color) {
        quad(x - a, y0, z - a, x + a, y0, z - a, x + b, y1, z - b, x - b, y1, z - b, 0, 0, -1, color);
        quad(x + a, y0, z + a, x - a, y0, z + a, x - b, y1, z + b, x + b, y1, z + b, 0, 0, 1, color);
        quad(x - a, y0, z + a, x - a, y0, z - a, x - b, y1, z - b, x - b, y1, z + b, -1, 0, 0, color);
        quad(x + a, y0, z - a, x + a, y0, z + a, x + b, y1, z + b, x + b, y1, z - b, 1, 0, 0, color);
    }

    private void rod(float x0, float y0, float z0, float x1, float y1, float z1, float r0, float r1, int color, int sides) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        pose.pushPose();
        pose.translate(x0, y0, z0);
        pose.mulPose(Axis.YP.rotation((float) Math.atan2(dx, dz)));
        pose.mulPose(Axis.XP.rotation((float) -Math.atan2(dy, Math.hypot(dx, dz))));
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        for (int i = 0; i < sides; i++) {
            float a = Mth.TWO_PI * i / sides, b = Mth.TWO_PI * (i + 1) / sides;
            float ca = Mth.cos(a), sa = Mth.sin(a), cb = Mth.cos(b), sb = Mth.sin(b);
            quad(r0 * ca, r0 * sa, 0, r0 * cb, r0 * sb, 0, r1 * cb, r1 * sb, length, r1 * ca, r1 * sa, length,
                    Mth.cos((a + b) / 2), Mth.sin((a + b) / 2), 0, color);
        }
        Mesh.ring(pose, mesh, 0, 0, 0, r0, 0, sides, -1, color, light);
        Mesh.ring(pose, mesh, 0, 0, length, r1, 0, sides, 1, color, light);
        pose.popPose();
    }

    private void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        Mesh.box(pose, mesh, x0, y0, z0, x1, y1, z1, color, light);
    }

    private void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
            float dx, float dy, float dz, float nx, float ny, float nz, int color) {
        Mesh.quad(pose, mesh, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, ny, nz, color, light);
    }

    private static int tint(int rgb, float amount) {
        return (int) ((rgb >> 16 & 255) * amount) << 16 | (int) ((rgb >> 8 & 255) * amount) << 8 | (int) ((rgb & 255) * amount);
    }
}
