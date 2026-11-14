package org.haruyq.createmediasystems.items.keytar.client;

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
 * The hayacio shoulder keyboard: 49 full-size keys on a slab body with a control neck, worn on a strap. Metres. x runs
 * along the keyboard with the bass and the neck at -x; y runs from the keys' free ends up to their hinge, which is the
 * upper edge when it is worn; the keys face -z.
 */
public final class KeytarMesh {
    public static final int FIRST_NOTE = 36, KEYS = 49;
    /** The keyboard: its two ends in x, the free ends and the hinge in y, and the playing surface in z. */
    public static final float KEY_LEFT = -.311f, KEY_PITCH = .0235f, KEY_RIGHT = KEY_LEFT + 29 * KEY_PITCH;
    public static final float KEY_FRONT = -.087f, KEY_HINGE = .048f, KEY_TOP = -.042f;
    /** Where the strap fastens: beside the neck's heel on the upper edge, and on the end of the tail. */
    public static final float NECK_PIN_X = -.345f, NECK_PIN_Y = .139f, TAIL_PIN_X = .482f, TAIL_PIN_Y = .012f, PIN_Z = 0;
    /** Where the left hand closes round the neck. */
    public static final float GRIP_X = -.660f, GRIP_Y = .045f, GRIP_Z = .014f;

    private static final float FACE = -.030f, BACK = .026f, NECK_BACK = .010f, SPLIT = KEY_FRONT + .050f;
    private static final float NECK_END = -.800f, HEEL = -.360f, TAIL = .475f;
    private static final int STATIONS = 120;
    private static final float[] X = new float[STATIONS + 1], TOP = new float[STATIONS + 1], BOTTOM = new float[STATIONS + 1];
    private static final float[] FRONT = new float[STATIONS + 1], REAR = new float[STATIONS + 1];
    private static final int SHELL = 0xEBEBE6, RIM = 0x25282D, GLASS = 0x0E1014, WELL = 0x131518, IVORY = 0xF4F2EA;
    private static final int EBONY = 0x17181B, METAL = 0xC6C9CE, RUBBER = 0x1B1D21, GLOW = 0x45E0CB, DIM = 0x15393A;
    /** How far each sharp sits from the seam between its neighbours, in key pitches, by pitch class. */
    private static final float[] OFFSET = {0, -.10f, 0, .10f, 0, 0, -.143f, 0, 0, 0, .143f, 0};

    /** How far down each key is, 0 at rest to 1 on the bed. */
    @FunctionalInterface
    public interface Keys {
        float travel(int note);
    }

    static {
        for (int i = 0; i <= STATIONS; i++) {
            float x = Mth.lerp(i / (float) STATIONS, NECK_END, TAIL);
            // Neck, a long swept shoulder into the body, then a tail rounded harder above than below.
            float shoulder = smooth((x - (HEEL - .075f)) / .130f), cutaway = smooth((x - (HEEL - .110f)) / .135f);
            float top = Mth.lerp(shoulder, .079f, .133f), bottom = Mth.lerp(cutaway, .013f, -.109f);
            if (x > .395f) top -= .121f * round((x - .395f) / (TAIL - .395f));
            if (x > .300f) bottom += .121f * round((x - .300f) / (TAIL - .300f));
            if (x < NECK_END + .034f) {
                float head = round(1 - (x - NECK_END) / .034f);
                top -= .033f * head;
                bottom += .033f * head;
            }
            X[i] = x;
            TOP[i] = top;
            BOTTOM[i] = bottom;
            FRONT[i] = FACE;
            REAR[i] = Mth.lerp(smooth((x - (HEEL - .040f)) / .090f), NECK_BACK, BACK);
        }
    }

    private KeytarMesh() {}

    /**
     * @param on    whether it is switched on: the display and lamps glow
     * @param bend  the neck's bender, -1 to 1
     * @param tone  which of the eight tone buttons is lit
     */
    public static void draw(PoseStack p, MultiBufferSource buffers, int light, Keys keys, boolean on, float bend, int tone) {
        VertexConsumer v = buffers.getBuffer(Mesh.solid());
        Shapes.slab(p, v, X, TOP, BOTTOM, FRONT, REAR, .007f, x -> SHELL, x -> RIM, x -> x < HEEL ? RUBBER : SHELL, light);
        keyboard(p, v, light, keys);
        panel(p, v, light, on, tone);
        neck(p, v, light, on, bend);
        fittings(p, v, light, on);
        // Text goes last, after every mesh vertex.
        Font font = Minecraft.getInstance().font;
        p.pushPose();
        p.translate(.424f, .036f, FACE - .0006f);
        p.mulPose(Axis.YP.rotationDegrees(180));
        p.scale(.0021f, -.0021f, .0021f);
        font.drawInBatch("hayacio", -font.width("hayacio") / 2f, 0, 0xFFB8923F, false, p.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, light);
        p.popPose();
    }

    private static void keyboard(PoseStack p, VertexConsumer v, int light, Keys keys) {
        box(p, v, KEY_LEFT - .007f, KEY_FRONT - .005f, FACE - .0015f, KEY_RIGHT + .007f, KEY_HINGE + .010f, FACE, WELL, light);
        box(p, v, KEY_LEFT - .003f, KEY_HINGE, KEY_TOP - .0008f, KEY_RIGHT + .003f, KEY_HINGE + .006f, FACE - .0015f, 0x2B6F68, light);
        int white = 0;
        for (int note = FIRST_NOTE; note < FIRST_NOTE + KEYS; note++) {
            boolean sharp = sharp(note);
            float seam = KEY_LEFT + white * KEY_PITCH, travel = keys.travel(note);
            if (travel > .003f) {
                p.pushPose();
                p.translate(0, KEY_HINGE, FACE - .006f);
                p.mulPose(Axis.XN.rotationDegrees(4.2f * travel));
                p.translate(0, -KEY_HINGE, -(FACE - .006f));
            }
            if (sharp) {
                float x = seam + OFFSET[note % 12] * KEY_PITCH;
                box(p, v, x - .0058f, SPLIT + .002f, KEY_TOP - .0065f, x + .0058f, KEY_HINGE, FACE - .001f, EBONY, light);
                box(p, v, x - .0050f, SPLIT + .008f, KEY_TOP - .0105f, x + .0050f, KEY_HINGE, KEY_TOP - .0065f, 0x1E2024, light);
            } else {
                float left = seam + .0006f, right = seam + KEY_PITCH - .0006f, rearLeft = left, rearRight = right;
                if (note > FIRST_NOTE && sharp(note - 1)) {
                    rearLeft = seam + OFFSET[(note - 1) % 12] * KEY_PITCH + .0064f;
                }
                if (note < FIRST_NOTE + KEYS - 1 && sharp(note + 1)) {
                    rearRight = seam + KEY_PITCH + OFFSET[(note + 1) % 12] * KEY_PITCH - .0064f;
                }
                box(p, v, left, KEY_FRONT, KEY_TOP, right, SPLIT, FACE - .001f, IVORY, light);
                box(p, v, rearLeft, SPLIT, KEY_TOP, rearRight, KEY_HINGE, FACE - .001f, IVORY, light);
                white++;
            }
            if (travel > .003f) p.popPose();
        }
    }

    /** The glass strip above the keys: tone buttons, display, two knobs and three transport keys. */
    private static void panel(PoseStack p, VertexConsumer v, int light, boolean on, int tone) {
        float glass = FACE - .0018f;
        box(p, v, -.318f, .064f, glass, .372f, .122f, FACE, GLASS, light);
        for (int i = 0; i < 8; i++) {
            float x = -.300f + i * .027f;
            box(p, v, x, .078f, glass - .0022f, x + .019f, .090f, glass, 0x2C3036, light);
            boolean lit = on && i == tone;
            box(p, v, x + .0065f, .0965f, glass - .0006f, x + .0125f, .0995f, glass, lit ? GLOW : DIM, lit ? Mesh.FULL_BRIGHT : light);
        }
        box(p, v, -.066f, .073f, glass - .0012f, .074f, .113f, glass, 0x1C2024, light);
        box(p, v, -.062f, .076f, glass - .0018f, .070f, .110f, glass - .0012f, on ? 0x0E3B3A : 0x090B0D, on ? Mesh.FULL_BRIGHT : light);
        if (on) {
            for (int i = 0; i < 14; i++) {
                float x = -.056f + i * .0088f, height = .004f + .020f * Math.abs(Mth.sin(i * 1.7f + tone));
                box(p, v, x, .080f, glass - .0022f, x + .0058f, .080f + height, glass - .0018f, GLOW, Mesh.FULL_BRIGHT);
            }
        }
        for (float x : new float[] {.118f, .166f}) {
            p.pushPose();
            p.translate(x, .093f, glass);
            p.mulPose(Axis.YP.rotationDegrees(180));
            Shapes.cylinder(p, v, .0135f, .004f, 20, 0x3A3E44, 0x3A3E44, light);
            p.translate(0, 0, .004f);
            Shapes.cylinder(p, v, .0112f, .012f, 20, METAL, 0xDADDE1, light);
            box(p, v, -.0011f, .003f, .012f, .0011f, .0108f, .0126f, 0x23262A, light);
            p.popPose();
        }
        for (int i = 0; i < 3; i++) {
            float x = .214f + i * .046f;
            box(p, v, x, .080f, glass - .0026f, x + .032f, .106f, glass, i == 2 ? 0x4A2226 : 0x2C3036, light);
        }
        box(p, v, .356f, .108f, glass - .0006f, .361f, .113f, glass, on ? GLOW : DIM, on ? Mesh.FULL_BRIGHT : light);
    }

    /** The neck's controls: a touch strip, the bender paddle, two octave keys and three assignable ones on the head. */
    private static void neck(PoseStack p, VertexConsumer v, int light, boolean on, float bend) {
        float glass = FACE - .0016f;
        box(p, v, -.704f, .031f, glass, -.474f, .061f, FACE, GLASS, light);
        for (int i = 0; i < 9; i++) {
            float x = -.694f + i * .0262f;
            box(p, v, x, .0445f, glass - .0005f, x + .004f, .0475f, glass, on ? GLOW : DIM, on ? Mesh.FULL_BRIGHT : light);
        }
        p.pushPose();
        p.translate(-.437f, .046f, FACE);
        p.mulPose(Axis.YP.rotationDegrees(13 * Mth.clamp(bend, -1, 1)));
        box(p, v, -.011f, -.027f, -.016f, .011f, .027f, 0, 0x30343A, light);
        box(p, v, -.0125f, -.029f, -.0185f, .0125f, .029f, -.016f, METAL, light);
        p.popPose();
        for (int i = 0; i < 2; i++) {
            float y = .020f + i * .030f;
            box(p, v, -.406f, y, glass - .0024f, -.384f, y + .022f, FACE, 0x2C3036, light);
        }
        for (int i = 0; i < 3; i++) {
            p.pushPose();
            p.translate(-.727f - i * .0205f, .046f, FACE);
            p.mulPose(Axis.YP.rotationDegrees(180));
            Shapes.cylinder(p, v, .0072f, .0034f, 14, 0x2C3036, i == 0 && on ? GLOW : 0x3A3E44, i == 0 && on ? Mesh.FULL_BRIGHT : light);
            p.popPose();
        }
        // A ribbed pad where the thumb rests behind the neck.
        for (int i = 0; i < 9; i++) {
            float x = -.690f + i * .024f;
            box(p, v, x, .020f, NECK_BACK, x + .014f, .072f, NECK_BACK + .0022f, 0x2A2D32, light);
        }
    }

    /** Strap pins, the lamp along the lower edge, the jacks under the tail and the battery door. */
    private static void fittings(PoseStack p, VertexConsumer v, int light, boolean on) {
        p.pushPose();
        p.translate(NECK_PIN_X, NECK_PIN_Y - .008f, PIN_Z);
        p.mulPose(Axis.XN.rotationDegrees(90));
        pin(p, v, light);
        p.popPose();
        p.pushPose();
        p.translate(TAIL_PIN_X - .009f, TAIL_PIN_Y, PIN_Z);
        p.mulPose(Axis.YP.rotationDegrees(90));
        pin(p, v, light);
        p.popPose();
        box(p, v, KEY_LEFT + .010f, -.1005f, FACE - .0012f, KEY_RIGHT - .060f, -.0975f, FACE, on ? GLOW : DIM, on ? Mesh.FULL_BRIGHT : light);
        for (int i = 0; i < 3; i++) {
            p.pushPose();
            p.translate(.300f + i * .034f, BOTTOM[index(.300f + i * .034f)] + .004f, .002f);
            p.mulPose(Axis.XP.rotationDegrees(90));
            Shapes.cylinder(p, v, i == 2 ? .0052f : .0078f, .0052f, 14, METAL, 0x0B0C0E, light);
            p.popPose();
        }
        box(p, v, -.120f, -.070f, BACK, .110f, .020f, BACK + .0016f, 0xDDDDD8, light);
        box(p, v, -.006f, -.066f, BACK + .0016f, .006f, -.058f, BACK + .0030f, 0x9C9F9F, light);
        box(p, v, .180f, -.075f, BACK, .360f, .095f, BACK + .0030f, RUBBER, light);
    }

    private static void pin(PoseStack p, VertexConsumer v, int light) {
        Shapes.cylinder(p, v, .0040f, .0085f, 12, METAL, METAL, light);
        p.translate(0, 0, .0085f);
        Shapes.cylinder(p, v, .0075f, .0030f, 12, METAL, 0xE0E2E5, light);
    }

    public static boolean sharp(int note) {
        return switch (note % 12) {
            case 1, 3, 6, 8, 10 -> true;
            default -> false;
        };
    }

    /** Where along x a key is pressed: the middle of a natural, the middle of a sharp. */
    public static float keyX(int note) {
        int naturals = 0;
        for (int n = FIRST_NOTE; n < note; n++) if (!sharp(n)) naturals++;
        float seam = KEY_LEFT + naturals * KEY_PITCH;
        return sharp(note) ? seam + OFFSET[note % 12] * KEY_PITCH : seam + KEY_PITCH / 2;
    }

    private static int index(float x) {
        return Mth.clamp(Math.round((x - NECK_END) / (TAIL - NECK_END) * STATIONS), 0, STATIONS);
    }

    private static float smooth(float amount) {
        float t = Mth.clamp(amount, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** A quarter turn: 0 at 0, rising ever faster to 1 at 1. */
    private static float round(float amount) {
        float t = Mth.clamp(amount, 0, 1);
        return 1 - Mth.sqrt(1 - t * t);
    }
}
