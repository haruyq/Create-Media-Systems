package org.haruyq.createmediasystems.items.instrument.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.haruyq.createmediasystems.items.instrument.InstrumentKind;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;

/** Client-side visual state an instrument performer can drive from their own note clock. */
public final class InstrumentAnimation {
    public enum Control {
        TREMOLO, SELECTOR, VOLUME, TONE_NECK, TONE_BRIDGE,
        THUMB, THUMB_BB, LEFT_INDEX, LEFT_MIDDLE, LEFT_RING, LEFT_PINKY,
        RIGHT_INDEX, RIGHT_MIDDLE, RIGHT_RING, RIGHT_PINKY, LOW_CS, LOW_C, TRILL_D, TRILL_DS, SIDE_BB,
        BB_CUP, LOWER_G, FS_CUP, CS_CUP,
        VALVE_1, VALVE_2, VALVE_3, FIRST_SLIDE, THIRD_SLIDE, MAIN_SLIDE, MAIN_WATER, THIRD_WATER;

        private static final Map<String, Control> NAMES = new HashMap<>();
        static {
            for (var control : values()) NAMES.put(control.name().toLowerCase(Locale.ROOT), control);
        }
        static Control named(String name) { return NAMES.get(name); }
    }

    public static final int STRINGS = 6;
    private static final float[] REST_CONTROLS = new float[Control.values().length];
    static {
        REST_CONTROLS[Control.VOLUME.ordinal()] = 1;
        REST_CONTROLS[Control.TONE_NECK.ordinal()] = 1;
        REST_CONTROLS[Control.TONE_BRIDGE.ordinal()] = 1;
    }
    private static final Control[] FLUTE_FINGERS = {
        Control.THUMB, Control.THUMB_BB, Control.LEFT_INDEX, Control.LEFT_MIDDLE, Control.LEFT_RING, Control.LEFT_PINKY,
        Control.RIGHT_INDEX, Control.RIGHT_MIDDLE, Control.RIGHT_RING, Control.RIGHT_PINKY, Control.LOW_CS, Control.LOW_C,
        Control.TRILL_D, Control.TRILL_DS, Control.SIDE_BB
    };
    private static final Control[] VALVES = {Control.VALVE_1, Control.VALVE_2, Control.VALVE_3};
    private static final long NO_STRIKE = Long.MIN_VALUE;

    public record Frame(float[] controls, int[] frets, float[] pressures, float[] bends, float[] vibrations,
                        float stroke, float fretHand, float playing) {
        public float control(Control control) {
            if (control == null) return 0;
            return switch (control) {
                case THUMB -> Math.max(raw(Control.THUMB), raw(Control.THUMB_BB));
                case BB_CUP -> Math.max(Math.max(raw(Control.LEFT_MIDDLE), raw(Control.SIDE_BB)),
                        Math.max(raw(Control.THUMB_BB), Math.min(raw(Control.LEFT_INDEX), raw(Control.RIGHT_INDEX))));
                case LOWER_G -> Math.max(raw(Control.LEFT_RING), raw(Control.RIGHT_MIDDLE));
                case FS_CUP -> Math.max(raw(Control.RIGHT_INDEX), Math.max(raw(Control.RIGHT_MIDDLE), raw(Control.RIGHT_RING)));
                case CS_CUP -> Math.max(raw(Control.LOW_CS), raw(Control.LOW_C));
                default -> raw(control);
            };
        }
        private float raw(Control control) { return controls[control.ordinal()]; }
    }

    private static final Frame REST = new Frame(REST_CONTROLS, new int[STRINGS], new float[STRINGS],
            new float[STRINGS], new float[STRINGS], .65f, 4, 0);
    private final Channel[] controls = new Channel[Control.values().length];
    private final Channel[] presses = new Channel[STRINGS];
    private final Channel[] bends = new Channel[STRINGS];
    private final int[] frets = new int[STRINGS], playedStrings = new int[STRINGS];
    private final int[] inputPitch = new int[STRINGS];
    private final long[] plucked = new long[STRINGS];
    private final float[] strengths = new float[STRINGS];
    private final Channel engaged = new Channel(0, .18);
    private final Channel fretHand = new Channel(4, .105);
    private final InstrumentKind kind;
    private long strokeStarted = NO_STRIKE;
    private boolean down;

    public InstrumentAnimation(InstrumentKind kind) {
        this.kind = kind;
        for (int i = 0; i < controls.length; i++) controls[i] = new Channel(REST_CONTROLS[i], .047);
        for (int i = 0; i < STRINGS; i++) {
            presses[i] = new Channel(0, .042);
            bends[i] = new Channel(0, .075);
        }
        Arrays.fill(plucked, NO_STRIKE);
        Arrays.fill(playedStrings, -1);
        Arrays.fill(inputPitch, -1);
    }

    void follow(InstrumentState state, int message, long now) {
        if (kind != InstrumentKind.STRATOCASTER) {
            int pitch = state.windPitch();
            if (pitch < 0) {
                releaseFingering(now);
                if (kind == InstrumentKind.TRUMPET) set(Control.THIRD_SLIDE, 0, now);
            } else {
                int written = kind == InstrumentKind.TRUMPET ? pitch + 2 : pitch;
                writtenNote(written, now);
                if (kind == InstrumentKind.TRUMPET) {
                    set(Control.THIRD_SLIDE, written == 61 || written == 62 ? .52f : 0, now);
                }
            }
            return;
        }
        set(Control.SELECTOR, state.pickup() / 4f, now);
        set(Control.VOLUME, state.volume() / 127f, now);
        set(Control.TONE_NECK, state.neckTone() / 127f, now);
        set(Control.TONE_BRIDGE, state.bridgeTone() / 127f, now);
        float bend = (state.bend() - InstrumentState.CENTER_BEND) / 8192f;
        set(Control.TREMOLO, Math.max(0, -bend), now);
        int attack = InstrumentState.command(message) == InstrumentState.NOTE && InstrumentState.second(message) > 0
                ? InstrumentState.first(message) : -1;
        for (int string = 0; string < STRINGS; string++) {
            if (inputPitch[string] >= 0) continue;
            int pitch = state.stringPitch(string);
            if (playedStrings[string] != pitch || pitch >= 0 && InstrumentState.command(message) == InstrumentState.BEND) {
                fret(string, InstrumentState.fret(string, pitch), Math.max(0, bend), now);
            }
            if (pitch >= 0 && (pitch == attack || playedStrings[string] != pitch)) {
                pluck(string, state.velocity(pitch) / 127f, now);
                if (pitch == attack) {
                    down = !down;
                    strokeStarted = now;
                }
            }
            playedStrings[string] = pitch;
        }
    }

    void inputFrame(byte[] frame, InstrumentState notes, long now) {
        int heldFrets = 0, position = 0;
        for (int s = 0; s < STRINGS; s++) {
            int i = s*3, strength = frame.length == 0 ? 0 : frame[i+1];
            if (strength == 0) {
                if (inputPitch[s] >= 0) {
                    inputPitch[s] = -1;
                    int pitch = notes.stringPitch(s);
                    fret(s, InstrumentState.fret(s,pitch), Math.max(0,(notes.bend()-8192)/8192f), now);
                    playedStrings[s] = pitch;
                }
                continue;
            }
            int fret = frame[i] & 127;
            int pitch = fret + InstrumentState.openString(s);
            boolean attack = frame[i] < 0 || inputPitch[s] != pitch;
            inputPitch[s] = pitch;
            fret(s,fret,Math.abs(frame[i+2])/100f,now);
            if (fret > 0) { heldFrets++; position += fret; }
            if (attack) {
                pluck(s,strength/127f,now);
                if (strokeStarted != now) down = !down;
                strokeStarted = now;
            }
        }
        if (heldFrets > 0) fretHand.to(position / (float) heldFrets, now);
    }

    boolean inputNote(int pitch) {
        for (int note : inputPitch) if (pitch == note) return true;
        return false;
    }

    public InstrumentKind kind() { return kind; }
    public static Frame rest() { return REST; }

    /** Normalized mechanical control travel. Selector positions are 0, .25, .5, .75 and 1. */
    public void set(Control control, float value, long now) {
        if (control == Control.SELECTOR) value = Math.round(clamp(value) * 4) / 4f;
        controls[control.ordinal()].to(clamp(value), now);
    }

    /** Raise a wind to the lips, or settle the guitar into the playing pose. */
    public void engage(boolean active, long now) {
        engaged.to(active ? 1 : 0, now);
    }

    /**
     * Finger a written pitch. For B-flat trumpet pass the written pitch, two semitones
     * above concert pitch. An out-of-range pitch leaves the preceding fingering alone.
     */
    public boolean writtenNote(int midi, long now) {
        var rig = InstrumentModel.rig(kind);
        return rig != null && writtenNote(rig.fingerings(), midi, now);
    }

    boolean writtenNote(Map<Integer, List<String>> chart, int midi, long now) {
        if (chart == null || !chart.containsKey(midi) || kind == InstrumentKind.STRATOCASTER) return false;
        setFingering(chart.get(midi), kind == InstrumentKind.FLUTE ? FLUTE_FINGERS : VALVES, now);
        return true;
    }

    public void releaseFingering(long now) {
        setFingering(List.of(), kind == InstrumentKind.FLUTE ? FLUTE_FINGERS : VALVES, now);
    }

    private void setFingering(List<String> notes, Control[] fingers, long now) {
        for (Control finger : fingers) {
            boolean active = false;
            for (String name : notes) if (Control.named(name) == finger) active = true;
            set(finger, active ? 1 : 0, now);
        }
    }

    /** Strings 0..5 run from the low E to the high E. Fret 0 releases the string. */
    public void fret(int string, int fret, float bend, long now) {
        if (!validString(string) || fret < 0 || fret > 22 || kind != InstrumentKind.STRATOCASTER) return;
        if (fret > 0) {
            frets[string] = fret;
            fretHand.to(fret, now);
        }
        presses[string].to(fret == 0 ? 0 : 1, now);
        bends[string].to(clamp(bend), now);
    }

    public void pluck(int string, float strength, long impact) {
        if (!validString(string) || !(clamp(strength) > 0) || kind != InstrumentKind.STRATOCASTER) return;
        plucked[string] = impact;
        strengths[string] = clamp(strength);
    }

    public void strum(boolean downstroke, float strength, long start) {
        if (kind != InstrumentKind.STRATOCASTER || !(clamp(strength) > 0)) return;
        strokeStarted = start;
        down = downstroke;
        for (int i = 0; i < STRINGS; i++) pluck(down ? i : STRINGS - 1 - i, strength, start + i * 8_000_000L);
    }

    public Frame sample(long now) {
        float[] values = new float[controls.length];
        for (int i = 0; i < values.length; i++) values[i] = controls[i].at(now);
        float[] pressure = new float[STRINGS], bent = new float[STRINGS], vibration = new float[STRINGS];
        for (int i = 0; i < STRINGS; i++) {
            pressure[i] = presses[i].at(now);
            bent[i] = bends[i].at(now);
            vibration[i] = plucked[i] == NO_STRIKE ? 0 : ring(secondsAfter(now, plucked[i]), strengths[i], i);
        }
        float stroke = strokeStarted == NO_STRIKE ? REST.stroke() : pick(secondsAfter(now, strokeStarted), down);
        return new Frame(values, frets.clone(), pressure, bent, vibration, stroke, fretHand.at(now), engaged.at(now));
    }

    static Frame fade(Frame last, float amount) {
        float[] controls = new float[REST_CONTROLS.length];
        for (int i = 0; i < controls.length; i++) {
            controls[i] = REST_CONTROLS[i] + amount * (last.controls()[i] - REST_CONTROLS[i]);
        }
        float[] pressure = new float[STRINGS];
        float[] vibration = new float[STRINGS];
        for (int i = 0; i < STRINGS; i++) {
            pressure[i] = last.pressures()[i] * amount;
            vibration[i] = last.vibrations()[i] * amount;
        }
        return new Frame(controls,last.frets(),pressure,last.bends(),vibration,
                REST.stroke() + amount*(last.stroke()-REST.stroke()),
                REST.fretHand() + amount*(last.fretHand()-REST.fretHand()),last.playing()*amount);
    }

    private static boolean validString(int string) { return string >= 0 && string < STRINGS; }
    private static double secondsAfter(long now, long start) { return (now - start) * 1e-9; }

    private static float ring(double age, float strength, int string) {
        if (age < 0 || age > 1.45) return 0;
        // Slow down the visual oscillation to keep it visible at game frame rates.
        return (float)(.0032 * strength * Math.exp(-age / .26) * Math.cos(age * Math.TAU * (17 + string * .8)));
    }

    private static float pick(double age, boolean down) {
        if (age < 0) return down ? .94f : -.94f;
        float crossing = 1 - 2 * smooth((float)(age / .074));
        return down ? crossing : -crossing;
    }

    static float smooth(float value) {
        float t = clamp(value);
        return t * t * (3 - 2 * t);
    }

    static float clamp(float value) { return Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0; }

    private static final class Channel {
        float from, target;
        long changed;
        private final double duration;

        Channel(float value, double duration) {
            from = target = value;
            this.duration = duration;
        }
        float at(long now) {
            return from + (target - from) * smooth((float)(secondsAfter(now, changed) / duration));
        }
        void to(float value, long now) {
            if (value == target) return;
            from = at(now);
            target = value;
            changed = now;
        }
    }
}
