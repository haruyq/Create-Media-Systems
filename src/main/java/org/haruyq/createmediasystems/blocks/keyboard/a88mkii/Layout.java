package org.haruyq.createmediasystems.blocks.keyboard.a88mkii;

import org.jetbrains.annotations.Nullable;

/**
 * Where everything is on the keyboard, for what draws it and what works it alike: its body (in sixteenths of a
 * block, across from the player's left, and from its back edge towards the player), its 88 keys, and the controls of
 * the pod at their left.
 */
public final class Layout {
    public static final int KEYS = 88;
    /** The MIDI notes of its lowest and highest key: A0 and C8. */
    public static final int LOWEST = 21, HIGHEST = LOWEST + KEYS - 1;
    public static final int WHITE_KEYS = 52;
    /** A black key's width in white keys, and how far down a white key it reaches. */
    public static final float BLACK_WIDTH = 0.58f, BLACK_LENGTH = 0.63f;

    /** The body: 3 blocks wide, as deep and high as the real one is to its width. */
    public static final float WIDTH = 48, DEPTH = 9.25f, HEIGHT = 3.4f;
    /** The pod of controls reaches from the left end to where the keys begin. */
    public static final float POD_LEFT = 0.35f, POD_RIGHT = 5.95f, POD_BACK = 0.3f, POD_FRONT = 8.95f;
    public static final float KEYS_LEFT = 6.1f, KEYS_RIGHT = 47.2f, KEYS_BACK = 2.9f, KEYS_FRONT = 8f;

    private static final int[] WHITES_BELOW = {0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6};

    private Layout() {
    }

    public static boolean black(int note) {
        return (0b010101001010 >> note % 12 & 1) != 0;
    }

    /**
     * Where {@code note}'s key is across the keys, in white keys from the left edge of the lowest: a white key's left
     * edge, a black key's middle (it sits on the line between two white ones).
     */
    public static int across(int note) {
        return note / 12 * 7 + WHITES_BELOW[note % 12] - 12;
    }

    public static float left(int note) {
        return black(note) ? across(note) - BLACK_WIDTH / 2 : across(note);
    }

    public static float width(int note) {
        return black(note) ? BLACK_WIDTH : 1;
    }

    /**
     * The note of the key at {@code across} white keys from the left and {@code along} its length (0 at the back, 1
     * at the front edge); -1 off the keys.
     */
    public static int keyAt(float across, float along) {
        if (across < 0 || across >= WHITE_KEYS || along < 0 || along > 1) {
            return -1;
        }
        if (along <= BLACK_LENGTH) {
            for (int note = LOWEST; note <= HIGHEST; note++) {
                if (black(note) && Math.abs(across - across(note)) <= BLACK_WIDTH / 2) {
                    return note;
                }
            }
        }
        for (int note = LOWEST; note <= HIGHEST; note++) {
            if (!black(note) && across(note) == (int) across) {
                return note;
            }
        }
        return -1;
    }

    /** How a control is worked. */
    public enum Kind {
        BUTTON, PAD, KNOB, LEVER
    }

    /**
     * The controls of the pod, each with its place on it: across from the pod's left (0..1) and down from its back
     * edge (0..1). Named as printed on the real thing.
     */
    public enum Control {
        BANK_DOWN(Kind.BUTTON, 0.07f, 0.035f, 0.19f, 0.075f),
        BANK_UP(Kind.BUTTON, 0.21f, 0.035f, 0.33f, 0.075f),
        SNAPSHOT(Kind.BUTTON, 0.80f, 0.035f, 0.94f, 0.075f),
        PAD_1(Kind.PAD, 0.07f, 0.105f, 0.235f, 0.2f),
        PAD_2(Kind.PAD, 0.255f, 0.105f, 0.42f, 0.2f),
        PAD_3(Kind.PAD, 0.44f, 0.105f, 0.605f, 0.2f),
        PAD_4(Kind.PAD, 0.625f, 0.105f, 0.79f, 0.2f),
        PAD_5(Kind.PAD, 0.07f, 0.215f, 0.235f, 0.31f),
        PAD_6(Kind.PAD, 0.255f, 0.215f, 0.42f, 0.31f),
        PAD_7(Kind.PAD, 0.44f, 0.215f, 0.605f, 0.31f),
        PAD_8(Kind.PAD, 0.625f, 0.215f, 0.79f, 0.31f),
        PREV(Kind.BUTTON, 0.83f, 0.115f, 0.94f, 0.19f),
        NEXT(Kind.BUTTON, 0.83f, 0.225f, 0.94f, 0.3f),
        PROGRAM(Kind.BUTTON, 0.07f, 0.345f, 0.215f, 0.385f),
        CC(Kind.BUTTON, 0.235f, 0.345f, 0.38f, 0.385f),
        NOTE(Kind.BUTTON, 0.4f, 0.345f, 0.545f, 0.385f),
        SHIFT(Kind.BUTTON, 0.63f, 0.345f, 0.775f, 0.385f),
        FUNC(Kind.BUTTON, 0.795f, 0.345f, 0.94f, 0.385f),
        KNOB_1(Kind.KNOB, 0.1f, 0.42f, 0.24f, 0.51f),
        KNOB_2(Kind.KNOB, 0.32f, 0.42f, 0.46f, 0.51f),
        KNOB_3(Kind.KNOB, 0.54f, 0.42f, 0.68f, 0.51f),
        KNOB_4(Kind.KNOB, 0.76f, 0.42f, 0.9f, 0.51f),
        KNOB_5(Kind.KNOB, 0.1f, 0.535f, 0.24f, 0.625f),
        KNOB_6(Kind.KNOB, 0.32f, 0.535f, 0.46f, 0.625f),
        KNOB_7(Kind.KNOB, 0.54f, 0.535f, 0.68f, 0.625f),
        KNOB_8(Kind.KNOB, 0.76f, 0.535f, 0.9f, 0.625f),
        LOWER(Kind.BUTTON, 0.07f, 0.66f, 0.215f, 0.7f),
        UPPER_1(Kind.BUTTON, 0.235f, 0.66f, 0.38f, 0.7f),
        UPPER_2(Kind.BUTTON, 0.4f, 0.66f, 0.545f, 0.7f),
        SPLIT(Kind.BUTTON, 0.565f, 0.66f, 0.71f, 0.7f),
        TRANSPOSE(Kind.BUTTON, 0.795f, 0.66f, 0.94f, 0.7f),
        S1(Kind.BUTTON, 0.07f, 0.73f, 0.215f, 0.77f),
        S2(Kind.BUTTON, 0.235f, 0.73f, 0.38f, 0.77f),
        OCTAVE_DOWN(Kind.BUTTON, 0.63f, 0.73f, 0.775f, 0.77f),
        OCTAVE_UP(Kind.BUTTON, 0.795f, 0.73f, 0.94f, 0.77f),
        LEVER(Kind.LEVER, 0.33f, 0.83f, 0.67f, 0.965f);

        public static final Control[] PADS = {PAD_1, PAD_2, PAD_3, PAD_4, PAD_5, PAD_6, PAD_7, PAD_8};
        public static final Control[] KNOBS = {KNOB_1, KNOB_2, KNOB_3, KNOB_4, KNOB_5, KNOB_6, KNOB_7, KNOB_8};
        public static final Control[] ZONES = {LOWER, UPPER_1, UPPER_2};

        public final Kind kind;
        public final float left, top, right, bottom;

        Control(Kind kind, float left, float top, float right, float bottom) {
            this.kind = kind;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        /** Which of its kind it is, from 0: the pad, the knob, the zone. */
        public int index() {
            return switch (kind) {
                case PAD -> ordinal() - PAD_1.ordinal();
                case KNOB -> ordinal() - KNOB_1.ordinal();
                default -> ordinal() - LOWER.ordinal();
            };
        }

        public float centerX() {
            return (left + right) / 2;
        }

        public float centerY() {
            return (top + bottom) / 2;
        }

        /** The control at ({@code x}, {@code y}) on the pod; null for none. */
        @Nullable
        public static Control at(float x, float y) {
            for (Control control : values()) {
                if (x >= control.left && x <= control.right && y >= control.top && y <= control.bottom) {
                    return control;
                }
            }
            return null;
        }
    }
}
