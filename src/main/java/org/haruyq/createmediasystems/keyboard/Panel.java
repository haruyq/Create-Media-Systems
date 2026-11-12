package org.haruyq.createmediasystems.keyboard;

import java.util.Arrays;

/**
 * How a keyboard's panel is set: its zones, split, octave and transpose, what its pads and knobs do and where the
 * knobs stand, its arpeggiator, and its eight snapshots. Kept by the block and seen by everyone near; whoever plays it
 * changes it. Saved and sent as {@link #bytes}.
 */
public final class Panel {
    public static final int LOWER = 0, UPPER_1 = 1, UPPER_2 = 2;
    /** What the pads send. */
    public static final int PROGRAMS = 0, CONTROLS = 1, NOTES = 2;
    public static final int BANKS = 16, PADS = 8, KNOBS = 8, SNAPSHOTS = 8;
    public static final int MIN_OCTAVE = -4, MAX_OCTAVE = 5, MIN_TRANSPOSE = -6, MAX_TRANSPOSE = 5, CURVES = 6;
    public static final int MIN_TEMPO = 40, MAX_TEMPO = 240;
    /** The controller each knob sends: cutoff, resonance, attack, release, reverb, chorus, pan, volume. */
    public static final int[] KNOB_CONTROLLERS = {74, 71, 73, 72, 91, 93, 10, 7};
    /** What a snapshot keeps: everything up to the knobs. */
    private static final int SNAPSHOT_BYTES = 24;
    private static final int BYTES = SNAPSHOT_BYTES + BANKS + 7 + 1 + SNAPSHOTS * SNAPSHOT_BYTES;

    /** Which zones sound, as bits, and the one the pads and knobs work. */
    public int zones = 1 << UPPER_1, target = UPPER_1;
    /** Split: LOWER plays up to {@code splitPoint} (a note), the UPPER zones above it. */
    public boolean split;
    public int splitPoint = 54;
    /** Each zone's MIDI channel (from 0) and program. */
    public final int[] channel = {2, 0, 1}, program = {32, 0, 48};
    public int octave, transpose, curve = 2;
    public int padMode = PROGRAMS;
    /** The pad bank of each pad mode. */
    public final int[] bank = new int[3];
    public final int[] knob = {64, 64, 64, 64, 40, 0, 64, 100};
    /** The pads latched on in each bank of the CC mode, as bits. */
    public final int[] latched = new int[BANKS];
    public boolean arpeggio, hold;
    /** The arpeggiator: octaves it runs over (1..4), its order (up, down, up and down, random), step and gate. */
    public int range = 1, order, step = 4, gate = 1, tempo = 120;
    /** Its snapshots, each as the first {@link #SNAPSHOT_BYTES} of {@link #bytes}; null where none is stored. */
    public final byte[][] snapshots = new byte[SNAPSHOTS][];

    public Panel() {
    }

    /** A panel as {@code bytes} say; what they leave out, or put out of range, is as on a new one. */
    public Panel(byte[] bytes) {
        byte[] all = Arrays.copyOf(bytes, BYTES);
        if (bytes.length >= SNAPSHOT_BYTES) {
            restore(all);
        }
        if (bytes.length < BYTES) {
            return;
        }
        int at = SNAPSHOT_BYTES;
        for (int i = 0; i < BANKS; i++) {
            latched[i] = all[at++] & 255;
        }
        arpeggio = all[at++] != 0;
        hold = all[at++] != 0;
        range = Math.clamp(all[at++], 1, 4);
        order = Math.clamp(all[at++], 0, 3);
        step = Math.clamp(all[at++], 0, 5);
        gate = Math.clamp(all[at++], 0, 1);
        tempo = Math.clamp(all[at++] & 255, MIN_TEMPO, MAX_TEMPO);
        int stored = all[at++];
        for (int i = 0; i < SNAPSHOTS; i++) {
            if ((stored >> i & 1) != 0) {
                snapshots[i] = Arrays.copyOfRange(all, at + i * SNAPSHOT_BYTES, at + (i + 1) * SNAPSHOT_BYTES);
            }
        }
    }

    public byte[] bytes() {
        byte[] all = Arrays.copyOf(snapshot(), BYTES);
        int at = SNAPSHOT_BYTES;
        for (int i = 0; i < BANKS; i++) {
            all[at++] = (byte) latched[i];
        }
        all[at++] = (byte) (arpeggio ? 1 : 0);
        all[at++] = (byte) (hold ? 1 : 0);
        all[at++] = (byte) range;
        all[at++] = (byte) order;
        all[at++] = (byte) step;
        all[at++] = (byte) gate;
        all[at++] = (byte) tempo;
        int stored = at++;
        for (int i = 0; i < SNAPSHOTS; i++) {
            if (snapshots[i] != null) {
                all[stored] |= (byte) (1 << i);
                System.arraycopy(snapshots[i], 0, all, at + i * SNAPSHOT_BYTES, SNAPSHOT_BYTES);
            }
        }
        return all;
    }

    /** What a snapshot keeps of it. */
    public byte[] snapshot() {
        byte[] kept = new byte[SNAPSHOT_BYTES];
        int at = 0;
        kept[at++] = (byte) (zones | target << 4);
        kept[at++] = (byte) (split ? 1 : 0);
        kept[at++] = (byte) splitPoint;
        for (int zone = 0; zone < 3; zone++) {
            kept[at++] = (byte) channel[zone];
        }
        for (int zone = 0; zone < 3; zone++) {
            kept[at++] = (byte) program[zone];
        }
        kept[at++] = (byte) octave;
        kept[at++] = (byte) transpose;
        kept[at++] = (byte) curve;
        kept[at++] = (byte) padMode;
        for (int mode = 0; mode < 3; mode++) {
            kept[at++] = (byte) bank[mode];
        }
        for (int i = 0; i < KNOBS; i++) {
            kept[at++] = (byte) knob[i];
        }
        return kept;
    }

    /** Sets what a snapshot keeps as {@code kept} has it. */
    public void restore(byte[] kept) {
        int at = 0;
        zones = kept[at] & 7;
        target = Math.clamp(kept[at++] >> 4 & 3, LOWER, UPPER_2);
        if (zones == 0) {
            zones = 1 << target;
        }
        split = kept[at++] != 0;
        splitPoint = Math.clamp(kept[at++], 0, 127);
        for (int zone = 0; zone < 3; zone++) {
            channel[zone] = kept[at++] & 15;
        }
        for (int zone = 0; zone < 3; zone++) {
            program[zone] = kept[at++] & 127;
        }
        octave = Math.clamp(kept[at++], MIN_OCTAVE, MAX_OCTAVE);
        transpose = Math.clamp(kept[at++], MIN_TRANSPOSE, MAX_TRANSPOSE);
        curve = Math.clamp(kept[at++], 0, CURVES - 1);
        padMode = Math.clamp(kept[at++], PROGRAMS, NOTES);
        for (int mode = 0; mode < 3; mode++) {
            bank[mode] = kept[at++] & BANKS - 1;
        }
        for (int i = 0; i < KNOBS; i++) {
            knob[i] = kept[at++] & 127;
        }
    }

    public boolean sounds(int zone) {
        return (zones >> zone & 1) != 0;
    }

    /** Whether {@code zone} plays the key of {@code note}: it sounds, and the key is on its side of a split. */
    public boolean plays(int zone, int note) {
        return sounds(zone) && (!split || zone == LOWER == note <= splitPoint);
    }

    /** The bank of the pad mode it is in. */
    public int bank() {
        return bank[padMode];
    }

    /** The General MIDI drum a pad of the note mode plays: the first bank a kit's mainstays, the rest in order. */
    public static int drum(int bank, int pad) {
        int[] kit = {36, 38, 42, 46, 45, 50, 49, 51};
        return bank == 0 ? kit[pad] : Math.min(127, 27 + bank * 8 + pad);
    }

    /** The controller a pad of the CC mode switches: the first bank the pedals and effects, the rest in order. */
    public static int padController(int bank, int pad) {
        int[] first = {64, 66, 67, 1, 91, 93, 80, 81};
        return bank == 0 ? first[pad] : Math.min(119, bank * 8 + pad);
    }
}
