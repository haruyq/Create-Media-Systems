package org.haruyq.createmediasystems.items.instrument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;

public final class InstrumentState {
    public static final int NOTE = 0x90, CONTROL = 0xB0, PROGRAM = 0xC0, BEND = 0xE0;
    public static final int BREATH = 2, SUSTAIN = 64, RELEASE_KEYS = 123;
    public static final int VOLUME = 7, PICKUP = 70, TONE_NECK = 71, TONE_BRIDGE = 74, AMP = 75;
    public static final int CENTER_BEND = 8192;
    private static final int[] PROGRAMS = {81, 89, 4, 16, 38, 62, 11, 50};

    private static final int[] OPEN_STRINGS = {40, 45, 50, 55, 59, 64};
    private static final int FRETS = 22;
    public final Kind kind;
    private final int[] velocity, sustained;
    private final int[] strings;
    private final List<Integer> windKeys = new ArrayList<>();
    private int lastNote, tone, bend = CENTER_BEND, breath, sustain;
    private int amp = 1;
    public int amp() { return amp; }
    private int volume = 127, pickup, neckTone = 127, bridgeTone = 127;

    public int volume() { return volume; }
    public int pickup() { return pickup; }
    public int neckTone() { return neckTone; }
    public int bridgeTone() { return bridgeTone; }

    /** The bridge has its own tone pot; the other pot serves middle and neck. */
    public int brightness() {
        float tone = switch (pickup) {
            case 0 -> bridgeTone;
            case 1 -> (bridgeTone + neckTone) * .5f;
            default -> neckTone;
        };
        return Math.round(25 + (53 - 4 * pickup) * tone / 127f);
    }

    public InstrumentState(Kind kind) {
        this.kind = kind;
        velocity = new int[kind.keys];
        sustained = new int[kind.keys];
        strings = kind == Kind.STRATOCASTER ? new int[OPEN_STRINGS.length] : new int[0];
        Arrays.fill(strings, -1);
        breath = kind.wind() ? 127 : 0;
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
    public int program() {
        return switch (kind) {
            case KEYTAR -> PROGRAMS[tone];
            case MELODICA -> 22;
            case STRATOCASTER -> 27; // General MIDI clean electric guitar
            case FLUTE -> 73;
            case TRUMPET -> 56;
        };
    }

    public int windPitch() { return windKeys.isEmpty() ? -1 : windKeys.getLast(); }
    public int stringPitch(int string) { return strings[string]; }
    public static int openString(int string) { return OPEN_STRINGS[string]; }
    public static int fret(int string, int pitch) { return pitch < 0 ? 0 : pitch - OPEN_STRINGS[string]; }

    /** The pitch uses concert MIDI numbering even on the B-flat trumpet. */
    public int voicedVelocity(int note) {
        if (kind.wind()) return breath > 0 && note == windPitch() ? velocity(note) : 0;
        if (kind == Kind.STRATOCASTER) {
            for (int pitch : strings) if (pitch == note) return velocity(note);
            return 0;
        }
        return velocity(note);
    }

    private void assignStrings() {
        Arrays.fill(strings, -1);
        // Assign the highest pitches first: they have the fewest strings that can reach them.
        for (int pitch = kind.last(); pitch >= kind.first; pitch--) {
            if (velocity(pitch) == 0) continue;
            for (int string = strings.length - 1; string >= 0; string--) {
                int fret = pitch - OPEN_STRINGS[string];
                if (strings[string] < 0 && fret >= 0 && fret <= FRETS) {
                    strings[string] = pitch;
                    break;
                }
            }
        }
    }
    public int velocity(int note) { return kind.contains(note) ? velocity[note - kind.first] : 0; }

    public boolean sounding() {
        if ((kind == Kind.MELODICA || kind.wind()) && breath == 0) return false;
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
                if (kind.wind()) {
                    windKeys.remove((Integer) a);
                    if (b != 0) windKeys.add(a);
                } else if (kind == Kind.STRATOCASTER) assignStrings();
            }
            case PROGRAM -> {
                if (kind != Kind.KEYTAR || a >= PROGRAMS.length) return false;
                tone = a;
            }
            case BEND -> {
                if (!kind.bendsPitch()) return false;
                bend = a | b << 7;
            }
            case CONTROL -> {
                if (a == RELEASE_KEYS) {
                    Arrays.fill(velocity, 0);
                    Arrays.fill(sustained, 0);
                    Arrays.fill(strings, -1);
                    windKeys.clear();
                    sustain = 0;
                    breath = kind.wind() ? 127 : 0;
                    bend = CENTER_BEND;
                } else if (a == BREATH && (kind == Kind.MELODICA || kind.wind())) breath = b;
                else if (a == SUSTAIN && kind == Kind.KEYTAR) {
                    sustain = b;
                    if (sustain < 64) Arrays.fill(sustained, 0);
                } else if (kind == Kind.STRATOCASTER) {
                    switch (a) {
                        case VOLUME -> volume = b;
                        case PICKUP -> pickup = Math.round(4 * b / 127f);
                        case TONE_NECK -> neckTone = b;
                        case TONE_BRIDGE -> bridgeTone = b;
                        case AMP -> amp = Math.round(b * 2 / 127f);
                        default -> { return false; }
                    }
                } else return false;
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
        } else if (kind.wind()) {
            messages.add(control(BREATH, breath));
            for (int note : windKeys) messages.add(note(note, velocity(note)));
            return messages;
        } else if (kind == Kind.STRATOCASTER) {
            messages.add(bend(bend));
            messages.add(control(VOLUME, volume));
            messages.add(control(PICKUP, Math.round(pickup * 127 / 4f)));
            messages.add(control(TONE_NECK, neckTone));
            messages.add(control(TONE_BRIDGE, bridgeTone));
            messages.add(control(AMP, Math.round(amp * 127 / 2f)));
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
