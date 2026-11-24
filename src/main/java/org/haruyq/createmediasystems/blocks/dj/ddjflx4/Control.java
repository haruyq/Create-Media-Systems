package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import org.jetbrains.annotations.Nullable;

/**
 * Everything on the DDJ-FLX4's top a hand works, where each is, and the MIDI message it sends (the numbers rekordbox's
 * own DDJ-FLX4 mapping lists). Places are measured on a top view 1000 wide in which the controller spans
 * {@link #LEFT}..{@link #RIGHT} across and {@link #BACK}..{@link #FRONT} from its back edge to its front: a control's
 * centre, and its width and depth. Most are there twice, once for each deck or channel: the second is the first moved
 * {@link #apart} to the right.
 */
public enum Control {
    LOOP_IN(Kind.BUTTON, 87, 300, 26, 26, 588, Port.DECK, 0x10, 0x4C),
    LOOP_OUT(Kind.BUTTON, 137, 300, 26, 26, 588, Port.DECK, 0x11, 0x4E),
    BEAT_4(Kind.BUTTON, 183, 299, 20, 20, 588, Port.DECK, 0x4D, 0x50),
    CALL_LEFT(Kind.BUTTON, 232, 300, 15, 15, 588, Port.DECK, 0x51, 0x3E),
    CALL_RIGHT(Kind.BUTTON, 268, 300, 15, 15, 588, Port.DECK, 0x53, 0x3D),
    SYNC(Kind.BUTTON, 314, 300, 22, 22, 588, Port.DECK, 0x58, 0x60),
    /** Turned: a relative control change, 0x40 for no movement. Its touch is {@link #JOG_TOUCH}. */
    JOG(Kind.JOG, 200, 462, 256, 256, 588, Port.DECK, 0x22, 0x29),
    SHIFT(Kind.BUTTON, 87, 590, 18, 14, 588, Port.DECK, 0x3F, -1),
    CUE(Kind.BUTTON, 90, 637, 42, 42, 588, Port.DECK, 0x0C, 0x48),
    PLAY(Kind.BUTTON, 90, 690, 42, 42, 588, Port.DECK, 0x0B, 0x0E),
    MODE_HOT_CUE(Kind.BUTTON, 140, 617, 33, 12, 588, Port.DECK, 0x1B, 0x69),
    MODE_PAD_FX(Kind.BUTTON, 182, 617, 33, 12, 588, Port.DECK, 0x1E, 0x6B),
    MODE_BEAT_JUMP(Kind.BUTTON, 223, 617, 33, 12, 588, Port.DECK, 0x20, 0x6D),
    MODE_SAMPLER(Kind.BUTTON, 264, 617, 33, 12, 588, Port.DECK, 0x22, 0x6F),
    PAD_1(Kind.PAD, 140, 658, 34, 34, 588, Port.PADS, 0, 0),
    PAD_2(Kind.PAD, 182, 658, 34, 34, 588, Port.PADS, 1, 1),
    PAD_3(Kind.PAD, 223, 658, 34, 34, 588, Port.PADS, 2, 2),
    PAD_4(Kind.PAD, 264, 658, 34, 34, 588, Port.PADS, 3, 3),
    PAD_5(Kind.PAD, 140, 700, 34, 34, 588, Port.PADS, 4, 4),
    PAD_6(Kind.PAD, 182, 700, 34, 34, 588, Port.PADS, 5, 5),
    PAD_7(Kind.PAD, 223, 700, 34, 34, 588, Port.PADS, 6, 6),
    PAD_8(Kind.PAD, 264, 700, 34, 34, 588, Port.PADS, 7, 7),
    /** Its place is its travel: pushed towards the front the tempo rises. */
    TEMPO(Kind.FADER, 316, 650, 26, 130, 588, Port.DECK, 0x00, 0x05),
    /** Deck 2's is the next note up. */
    LOAD(Kind.BUTTON, 435, 284, 26, 12, 120, Port.MIXER, 0x46, -1),
    /** Turned: a relative control change, 1 a step clockwise and 0x7F one back. Its press is {@link #BROWSE_PRESS}. */
    BROWSE(Kind.ENCODER, 495, 275, 30, 30, 0, Port.MIXER, 0x40, 0x64),
    TRIM(Kind.KNOB, 457, 320, 24, 24, 75, Port.DECK, 0x04, -1),
    MASTER_LEVEL(Kind.KNOB, 599, 320, 26, 26, 0, Port.MIXER, 0x08, -1),
    HI(Kind.KNOB, 457, 366, 28, 28, 75, Port.DECK, 0x07, -1),
    MID(Kind.KNOB, 457, 411, 28, 28, 75, Port.DECK, 0x0B, -1),
    LOW(Kind.KNOB, 457, 456, 28, 28, 75, Port.DECK, 0x0F, -1),
    /** Channel 2's is the next control up. */
    CFX(Kind.KNOB, 457, 502, 28, 28, 75, Port.MIXER, 0x17, -1),
    /** Works the controller's own microphone input: it sends nothing. */
    MIC_LEVEL(Kind.KNOB, 389, 411, 28, 28, 0, Port.NONE, 0, -1),
    SMART_CFX(Kind.BUTTON, 386, 502, 20, 20, 0, Port.MIXER, 0x00, 0x08),
    HEADPHONE_MIX(Kind.KNOB, 389, 571, 24, 24, 0, Port.MIXER, 0x0C, -1),
    HEADPHONE_LEVEL(Kind.KNOB, 389, 617, 24, 24, 0, Port.MIXER, 0x0D, -1),
    SMART_FADER(Kind.BUTTON, 386, 659, 20, 20, 0, Port.MIXER, 0x01, -1),
    MASTER_CUE(Kind.BUTTON, 599, 367, 26, 12, 0, Port.MIXER, 0x63, 0x78),
    CHANNEL_CUE(Kind.BUTTON, 457, 549, 26, 12, 75, Port.DECK, 0x54, 0x68),
    /** Its place is its travel: pushed towards the back it is loudest. */
    CHANNEL_FADER(Kind.FADER, 457, 628, 24, 92, 75, Port.DECK, 0x13, -1),
    CROSSFADER(Kind.CROSSFADER, 495, 712, 92, 24, 0, Port.MIXER, 0x1F, -1),
    /** Three places: channel 1, channel 2, both. It says which channel the beat FX's other controls send on. */
    FX_CHANNEL(Kind.SWITCH, 600, 457, 30, 14, 0, Port.NONE, 0, -1),
    FX_SELECT(Kind.BUTTON, 600, 503, 26, 12, 0, Port.FX, 0x63, 0x64),
    FX_BEAT_LEFT(Kind.BUTTON, 586, 549, 22, 12, 0, Port.FX, 0x4A, 0x66),
    FX_BEAT_RIGHT(Kind.BUTTON, 614, 549, 22, 12, 0, Port.FX, 0x4B, 0x6B),
    FX_DEPTH(Kind.KNOB, 599, 607, 26, 26, 0, Port.FX, 0x02, -1),
    FX_ON(Kind.BUTTON, 599, 659, 26, 26, 0, Port.FX, 0x47, 0x43);

    public enum Kind {
        /** Down or up. */
        BUTTON, PAD,
        /** 0 to {@link Control#FULL}: a knob from hard left to hard right, a fader from its near end to its far one. */
        KNOB, FADER, CROSSFADER,
        JOG, ENCODER,
        /** 0, 1 or 2. */
        SWITCH;

        public boolean continuous() {
            return this == KNOB || this == FADER || this == CROSSFADER;
        }

        public boolean pressed() {
            return this == BUTTON || this == PAD;
        }
    }

    /** Which MIDI channel a control sends on. */
    public enum Port {
        /** Channel 1 for deck 1, 2 for deck 2. */
        DECK,
        /** Channel 7. */
        MIXER,
        /** Channel 5 for the first beat FX, 6 for the second, as {@link Control#FX_CHANNEL} stands. */
        FX,
        /** Channel 8 for deck 1's pads and 10 for deck 2's, and one higher with SHIFT. The note is the pad mode's base plus the pad. */
        PADS,
        NONE
    }

    /** The pad modes in the order their notes are numbered: each has sixteen notes, of which the pads use eight. */
    public enum PadMode {
        HOT_CUE(Control.MODE_HOT_CUE, false), PAD_FX_1(Control.MODE_PAD_FX, false), BEAT_JUMP(Control.MODE_BEAT_JUMP, false),
        SAMPLER(Control.MODE_SAMPLER, false), KEYBOARD(Control.MODE_HOT_CUE, true), PAD_FX_2(Control.MODE_PAD_FX, true),
        BEAT_LOOP(Control.MODE_BEAT_JUMP, true), KEY_SHIFT(Control.MODE_SAMPLER, true);

        /** The button that selects it, with SHIFT if {@link #shifted}. */
        public final Control button;
        public final boolean shifted;

        PadMode(Control button, boolean shifted) {
            this.button = button;
            this.shifted = shifted;
        }

        public int note() {
            return ordinal() * 16;
        }

        @Nullable
        public static PadMode of(Control button, boolean shifted) {
            for (PadMode mode : values()) {
                if (mode.button == button && mode.shifted == shifted) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** The top view's bounds of the controller. */
    public static final float LEFT = 48, RIGHT = 942, BACK = 252, FRONT = 747;
    /** A continuous control's highest value: they send fourteen bits. */
    public static final int FULL = 0x3FFF;
    /** How far (degrees) a knob turns from hard left to hard right. */
    public static final float KNOB_SWEEP = 300;
    /** Note numbers on a deck's channel that belong to no one control here. */
    public static final int JOG_TOUCH = 0x36, JOG_TOUCH_SHIFTED = 0x67, SYNC_LONG = 0x5C;
    /** The jog wheel's other control changes: its outer ring, and its top with vinyl mode off. */
    public static final int JOG_RING = 0x21, JOG_BEND = 0x23;
    /** Note numbers on the mixer's channel for the browse knob's press. */
    public static final int BROWSE_PRESS = 0x41, BROWSE_PRESS_SHIFTED = 0x42;
    /** LOAD with SHIFT, for deck 1 and deck 2. */
    public static final int LOAD_SHIFTED_1 = 0x68, LOAD_SHIFTED_2 = 0x7A;
    /** Steps of a jog wheel in one turn. */
    public static final int JOG_STEPS = 720;
    private static final Control[] ALL = values();
    /** One slot for every control of every deck. */
    public static final int SLOTS = ALL.length * 2;

    public final Kind kind;
    public final float x, y, width, depth;
    /** How far right the second one is; 0 if there is only one. */
    public final float apart;
    public final Port port;
    /** Its note or control number, and the one it sends with SHIFT held (-1: the same). */
    public final int number, shifted;

    Control(Kind kind, float x, float y, float width, float depth, float apart, Port port, int number, int shifted) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.width = width;
        this.depth = depth;
        this.apart = apart;
        this.port = port;
        this.number = number;
        this.shifted = shifted;
    }

    public int count() {
        return apart == 0 ? 1 : 2;
    }

    /** Where its state is kept (see Panel): {@code deck} is 0 or 1. */
    public int slot(int deck) {
        return ordinal() * 2 + deck;
    }

    public static Control ofSlot(int slot) {
        return ALL[slot >> 1];
    }

    public float x(int deck) {
        return x + deck * apart;
    }

    /** Where a continuous control stands with nobody having moved it: the middle, or for levels a working loudness. */
    public int rest() {
        return switch (this) {
            case CHANNEL_FADER -> FULL;
            case MASTER_LEVEL, HEADPHONE_LEVEL -> FULL * 3 / 4;
            case MIC_LEVEL, FX_DEPTH -> 0;
            default -> kind.continuous() ? FULL / 2 + 1 : 0;
        };
    }

    /** The control at ({@code px}, {@code py}) of the top view and which of its two; -1 for none. Returns its slot. */
    public static int at(float px, float py) {
        int best = -1;
        float nearest = Float.MAX_VALUE;
        for (Control control : ALL) {
            for (int deck = 0; deck < control.count(); deck++) {
                float dx = Math.abs(px - control.x(deck)), dy = Math.abs(py - control.y);
                // The jog wheel is round, and lies under nothing else.
                boolean inside = control.kind == Kind.JOG ? dx * dx + dy * dy <= control.width * control.width / 4
                        : dx <= control.width / 2 + 2 && dy <= control.depth / 2 + 2;
                if (inside && dx + dy < nearest) {
                    nearest = dx + dy;
                    best = control.slot(deck);
                }
            }
        }
        return best;
    }
}
