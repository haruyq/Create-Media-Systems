package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Controller;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout.Control;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Panel;

import org.jetbrains.annotations.Nullable;

/**
 * How a keyboard's panel looks as it is set: which buttons and lights are lit and in what colour, for the block in
 * the world and the screen of its player alike. {@code controller} is the player's own, who alone sees what they have
 * armed (SHIFT, FUNC, a snapshot being picked); null for everyone else.
 */
record PanelLook(Panel panel, int padsDown, @Nullable Controller controller) {
    static final int BODY = 0x1C1C1F, DECK = 0x242428, BUTTON = 0x3A3A40, KNOB = 0x2C2C31, WHITE_KEY = 0xF1EEE6,
            BLACK_KEY = 0x121214, FELT = 0x7A1220, LEGEND = 0xC8C8CC;
    static final int RED = 0xFF4034, ORANGE = 0xFF9A1F, GREEN = 0x3DE06A, BLUE = 0x2E9BFF, PALE = 0xE4E9FF, WHITE = 0xFFFFFF;
    /** The pads and knob rings light in pairs and columns of these, as on the real one. */
    private static final int[] COLUMNS = {RED, ORANGE, GREEN, BLUE};

    private int snapshotMode() {
        return controller == null ? 0 : controller.snapshotMode();
    }

    int padColor(int pad) {
        return snapshotMode() != 0 ? PALE : panel.arpeggio ? GREEN : COLUMNS[pad % 4 / 2 + pad / 4 * 2];
    }

    /** Whether pad {@code pad} is lit bright: held down, or what it picks is picked. */
    boolean padLit(int pad) {
        if ((padsDown >> pad & 1) != 0) {
            return true;
        }
        if (snapshotMode() != 0) {
            return controller.snapshotPad() == pad;
        }
        if (panel.arpeggio) {
            return pad < 4 ? panel.range == pad + 1 : panel.order == pad - 4;
        }
        return panel.padMode == Panel.PROGRAMS ? panel.program[panel.target] == panel.bank() * Panel.PADS + pad
                : panel.padMode == Panel.CONTROLS && (panel.latched[panel.bank()] >> pad & 1) != 0;
    }

    /** Whether pad {@code pad} glows at all: every pad does, but in snapshot mode only those with one stored. */
    boolean padGlows(int pad) {
        return snapshotMode() == 0 || panel.snapshots[pad] != null || padLit(pad);
    }

    int knobColor(int knob) {
        return COLUMNS[knob % 4];
    }

    boolean lit(Control control) {
        return switch (control) {
            case PROGRAM -> panel.padMode == Panel.PROGRAMS;
            case CC -> panel.padMode == Panel.CONTROLS;
            case NOTE -> panel.padMode == Panel.NOTES;
            case LOWER, UPPER_1, UPPER_2 -> panel.sounds(control.index());
            case SPLIT -> panel.split || controller != null && controller.lit(control);
            case TRANSPOSE -> panel.transpose != 0 || controller != null && controller.lit(control);
            case S1 -> panel.arpeggio && panel.hold;
            case OCTAVE_DOWN -> panel.octave < 0;
            case OCTAVE_UP -> panel.octave > 0;
            default -> controller != null && controller.lit(control);
        };
    }

    /** The colour of bank light {@code light} (0..7), or -1 if it is off: white for banks 1 to 8, red for 9 to 16. */
    int bankLight(int light) {
        return panel.bank() % 8 != light ? -1 : panel.bank() < 8 ? WHITE : RED;
    }

    /** {@code color} at {@code share} of its brightness: dimmer below 1, brighter above. */
    static int dim(int color, float share) {
        return Math.min(255, (int) ((color >> 16 & 255) * share)) << 16 | Math.min(255, (int) ((color >> 8 & 255) * share)) << 8
                | Math.min(255, (int) ((color & 255) * share));
    }
}
