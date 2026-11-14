package org.haruyq.createmediasystems.items.instrument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;

public final class InstrumentState {
    public static final int NOTE = 0x90, CONTROL = 0xB0, PROGRAM = 0xC0, BEND = 0xE0;
    public static final int BREATH = 2, SUSTAIN = 64, RELEASE_KEYS = 123;
    public static final int CENTER_BEND = 8192;
    private static final int[] PROGRAMS = {81, 89, 4, 16, 38, 62, 11, 50};

    public final Kind kind;
    private final int[] velocity, sustained;
    private int lastNote, tone, bend = CENTER_BEND, breath, sustain;

    public InstrumentState(Kind kind) {
        this.kind = kind;
        velocity = new int[kind.keys];
        sustained = new int[kind.keys];
        lastNote = 60;
    }

    public static int pack(int command, int first, int second) { return command | first << 8 | second << 16; }
    public static int command(int message) { return message & 255; }
    public static int first(int message) { return message >> 8 & 127; }
    public static int second(int message) { return message >> 16 & 127; }
    public static int note(int note, int velocity) { return pack(NOTE, note, velocity); }
    public static int control(int cc, int value) { return pack(CONTROL, cc, value); }
    public static int tone(int index) { return pack(PROGRAM, index, 0); }
    public static int bend(int bend) { return pack(BEND, bend & 127, bend >> 7); }

    public int lastNote() { return lastNote; }
    public int tone() { return tone; }
    public int bend() { return bend; }
    public int sustain() { return sustain; }
    public int breath() { return breath; }
    public int program() { return kind == Kind.MELODICA ? 22 : PROGRAMS[tone]; }
    public int velocity(int note) { return kind.contains(note) ? velocity[note - kind.first] : 0; }

    public boolean sounding() {
        if (kind == Kind.MELODICA && breath == 0) return false;
        for (int value : velocity) if (value != 0) return true;
        for (int value : sustained) if (value != 0) return true;
        return false;
    }

    public boolean apply(int message) {
        if ((message & ~0x7f7fff) != 0) return false;
        int a = first(message), b = second(message);
        switch (command(message)) {
            case NOTE -> {
                if (!kind.contains(a)) return false;
                int key = a - kind.first;
                if (b != 0) {
                    lastNote = a;
                    sustained[key] = 0;
                } else if (sustain >= 64 && velocity[key] != 0) {
                    sustained[key] = velocity[key];
                }
                velocity[key] = b;
            }
            case PROGRAM -> {
                if (kind != Kind.KEYTAR || a >= PROGRAMS.length) return false;
                tone = a;
            }
            case BEND -> {
                if (kind != Kind.KEYTAR) return false;
                bend = a | b << 7;
            }
            case CONTROL -> {
                if (a == RELEASE_KEYS) {
                    Arrays.fill(velocity, 0);
                    Arrays.fill(sustained, 0);
                    sustain = breath = 0;
                    bend = CENTER_BEND;
                } else if (a == BREATH && kind == Kind.MELODICA) breath = b;
                else if (a == SUSTAIN && kind == Kind.KEYTAR) {
                    sustain = b;
                    if (sustain < 64) Arrays.fill(sustained, 0);
                }
                else return false;
            }
            default -> { return false; }
        }
        return true;
    }

    public List<Integer> snapshot() {
        var messages = new ArrayList<Integer>();
        if (kind == Kind.KEYTAR) {
            messages.add(tone(tone));
            messages.add(bend(bend));
            if (sustain > 0) messages.add(control(SUSTAIN, sustain));
        } else if (breath > 0) messages.add(control(BREATH, breath));
        for (int n = kind.first; n <= kind.last(); n++) {
            if (velocity(n) != 0) messages.add(note(n, velocity(n)));
            else if (sustained[n - kind.first] != 0) {
                messages.add(note(n, sustained[n - kind.first]));
                messages.add(note(n, 0));
            }
        }
        return messages;
    }
}
