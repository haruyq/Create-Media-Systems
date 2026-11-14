package org.haruyq.createmediasystems.items.melodica.client;

import static org.haruyq.createmediasystems.client.mesh.Mesh.box;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.mesh.Shapes;

/**
 * A 32-key melodica, F3 to C6, blown through a short mouthpiece at its bass end. Metres. x runs along the keyboard with
 * the bass and the mouthpiece at -x; y runs from the keys' free ends up to their hinge; the keys face -z, and the hand
 * strap is on the back at +z.
 */
public final class MelodicaMesh {
    public static final int FIRST_NOTE = 53, KEYS = 32;
    public static final float KEY_LEFT = -.1862f, KEY_PITCH = .0196f, KEY_RIGHT = KEY_LEFT + 19 * KEY_PITCH;
    public static final float KEY_FRONT = -.046f, KEY_HINGE = .020f, KEY_TOP = -.030f;
    /** The tip of the mouthpiece, where the lips close. */
    public static final float MOUTH_X = -.292f, MOUTH_Y = .006f, MOUTH_Z = .001f;
    /** The middle of the hand strap, where the holding hand's palm lies against the back. */
    public static final float HOLD_X = .010f, HOLD_Y = 0, HOLD_Z = .034f;

    private static final float HALF = .210f, EDGE = .0525f, FACE = -.021f, BACK = .023f, SPLIT = KEY_FRONT + .030f;
    private static final int STATIONS = 48;
    private static final float[] X = new float[STATIONS + 1], TOP = new float[STATIONS + 1], BOTTOM = new float[STATIONS + 1];
    private static final float[] FRONT = new float[STATIONS + 1], REAR = new float[STATIONS + 1];
    private static final int SHELL = 0x2F7FC1, SHADE = 0x265F94, CAP = 0x1B1E23, IVORY = 0xF4F2EA, EBONY = 0x17181B;
    private static final int METAL = 0xC6C9CE, WEB = 0x15171A, WHITE = 0xEDEDE8;
    private static final float[] OFFSET = {0, -.10f, 0, .10f, 0, 0, -.143f, 0, 0, 0, .143f, 0};

    /** How far down each key is, 0 at rest to 1 on the bed. */
    @FunctionalInterface
    public interface Keys {
        float travel(int note);
    }

    static {
        for (int i = 0; i <= STATIONS; i++) {
            // Stations crowd towards the ends, where the corners turn.
            float t = i / (float) STATIONS, x = HALF * (float) -Math.cos(t * Math.PI);
            float corner = Math.max(0, (Math.abs(x) - (HALF - .020f)) / .020f);
            float cut = .020f * (1 - Mth.sqrt(Math.max(0, 1 - corner * corner)));
            X[i] = x;
            TOP[i] = EDGE - cut;
            BOTTOM[i] = -EDGE + cut;
            FRONT[i] = FACE;
            REAR[i] = BACK;
        }
    }

    private MelodicaMesh() {}

    public static void draw(PoseStack p, MultiBufferSource buffers, int light, Keys keys) {
        VertexConsumer v = buffers.getBuffer(Mesh.solid());
        Shapes.slab(p, v, X, TOP, BOTTOM, FRONT, REAR, .006f, x -> Math.abs(x) > HALF - .014f ? CAP : SHELL,
                x -> Math.abs(x) > HALF - .014f ? CAP : SHADE, x -> Math.abs(x) > HALF - .014f ? CAP : SHELL, light);
        keyboard(p, v, light, keys);
        mouthpiece(p, v, light);
        back(p, v, light);
        Font font = Minecraft.getInstance().font;
        p.pushPose();
        p.translate(.118f, .0445f, FACE - .0006f);
        p.mulPose(Axis.YP.rotationDegrees(180));
        p.scale(.0019f, -.0019f, .0019f);
        font.drawInBatch("hayacio", -font.width("hayacio") / 2f, 0, 0xFFF1E6C4, false, p.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, light);
        p.popPose();
    }

    private static void keyboard(PoseStack p, VertexConsumer v, int light, Keys keys) {
        box(p, v, KEY_LEFT - .004f, KEY_FRONT - .0035f, FACE - .0012f, KEY_RIGHT + .004f, KEY_HINGE + .006f, FACE, 0x101216, light);
        box(p, v, KEY_LEFT - .004f, KEY_HINGE, KEY_TOP - .0015f, KEY_RIGHT + .004f, KEY_HINGE + .0075f, FACE - .0012f, METAL, light);
        int white = 0;
        for (int note = FIRST_NOTE; note < FIRST_NOTE + KEYS; note++) {
            float seam = KEY_LEFT + white * KEY_PITCH, travel = keys.travel(note);
            if (travel > .003f) {
                p.pushPose();
                p.translate(0, KEY_HINGE, FACE - .004f);
                p.mulPose(Axis.XN.rotationDegrees(4.3f * travel));
                p.translate(0, -KEY_HINGE, -(FACE - .004f));
            }
            if (sharp(note)) {
                float x = seam + OFFSET[note % 12] * KEY_PITCH;
                box(p, v, x - .0049f, SPLIT + .0015f, KEY_TOP - .0050f, x + .0049f, KEY_HINGE, FACE - .001f, EBONY, light);
                box(p, v, x - .0042f, SPLIT + .0055f, KEY_TOP - .0078f, x + .0042f, KEY_HINGE, KEY_TOP - .0050f, 0x1E2024, light);
            } else {
                float left = seam + .0005f, right = seam + KEY_PITCH - .0005f, rearLeft = left, rearRight = right;
                if (note > FIRST_NOTE && sharp(note - 1)) rearLeft = seam + OFFSET[(note - 1) % 12] * KEY_PITCH + .0054f;
                if (note < FIRST_NOTE + KEYS - 1 && sharp(note + 1)) {
                    rearRight = seam + KEY_PITCH + OFFSET[(note + 1) % 12] * KEY_PITCH - .0054f;
                }
                box(p, v, left, KEY_FRONT, KEY_TOP, right, SPLIT, FACE - .001f, IVORY, light);
                box(p, v, rearLeft, SPLIT, KEY_TOP, rearRight, KEY_HINGE, FACE - .001f, IVORY, light);
                white++;
            }
            if (travel > .003f) p.popPose();
        }
    }

    /** A socket in the bass end, a short tube, and a flattened tip for the lips. */
    private static void mouthpiece(PoseStack p, VertexConsumer v, int light) {
        p.pushPose();
        p.translate(-HALF + .002f, MOUTH_Y, MOUTH_Z);
        p.mulPose(Axis.YN.rotationDegrees(90));
        Shapes.cylinder(p, v, .0125f, .016f, 16, CAP, CAP, light);
        p.translate(0, 0, .016f);
        Shapes.cylinder(p, v, .0085f, .040f, 16, WHITE, WHITE, light);
        p.popPose();
        float from = -HALF - .052f;
        box(p, v, MOUTH_X, MOUTH_Y - .0045f, MOUTH_Z - .0100f, from + .004f, MOUTH_Y + .0045f, MOUTH_Z + .0100f, WHITE, light);
        box(p, v, from, MOUTH_Y - .0070f, MOUTH_Z - .0095f, from + .010f, MOUTH_Y + .0070f, MOUTH_Z + .0095f, WHITE, light);
    }

    /** The back: reed cover with its slots, the water key at the treble end, and the hand strap. */
    private static void back(PoseStack p, VertexConsumer v, int light) {
        box(p, v, -.172f, -.038f, BACK, .172f, .038f, BACK + .0014f, SHADE, light);
        for (int i = 0; i < 9; i++) {
            float x = -.150f + i * .036f;
            if (Math.abs(x + .006f) < .060f) continue;
            box(p, v, x, -.026f, BACK + .0014f, x + .020f, -.022f, BACK + .0020f, 0x0F1113, light);
            box(p, v, x, .022f, BACK + .0014f, x + .020f, .026f, BACK + .0020f, 0x0F1113, light);
        }
        p.pushPose();
        p.translate(HALF - .003f, -.024f, .004f);
        p.mulPose(Axis.YP.rotationDegrees(90));
        Shapes.cylinder(p, v, .0058f, .0050f, 12, METAL, 0xE0E2E5, light);
        p.popPose();
        // The strap bows away from the back so a hand slides under it.
        int steps = 14;
        float[] path = new float[(steps + 1) * 3], out = new float[(steps + 1) * 3];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps, x = Mth.lerp(t, -.118f, .138f), bow = Math.max(0, Mth.sin(t * Mth.PI));
            path[i * 3] = x;
            path[i * 3 + 1] = HOLD_Y;
            path[i * 3 + 2] = BACK + .0016f + .019f * (float) Math.pow(bow, .6);
            float slope = .019f * Mth.cos(t * Mth.PI) * Mth.PI / .256f, length = Mth.sqrt(1 + slope * slope);
            out[i * 3] = -slope / length;
            out[i * 3 + 2] = 1 / length;
        }
        Shapes.ribbon(p, v, path, out, .019f, WEB, light);
        box(p, v, -.126f, -.021f, BACK + .0014f, -.112f, .021f, BACK + .0050f, CAP, light);
        box(p, v, .132f, -.021f, BACK + .0014f, .146f, .021f, BACK + .0050f, CAP, light);
    }

    public static boolean sharp(int note) {
        return switch (note % 12) {
            case 1, 3, 6, 8, 10 -> true;
            default -> false;
        };
    }

    /** Where along x a key is pressed. */
    public static float keyX(int note) {
        int naturals = 0;
        for (int n = FIRST_NOTE; n < note; n++) if (!sharp(n)) naturals++;
        float seam = KEY_LEFT + naturals * KEY_PITCH;
        return sharp(note) ? seam + OFFSET[note % 12] * KEY_PITCH : seam + KEY_PITCH / 2;
    }
}
