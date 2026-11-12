package org.haruyq.createmediasystems.keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.keyboard.Layout.Control;

/**
 * The keyboard as its player works it: turns keys, pads, knobs, buttons and the lever into the MIDI messages its
 * {@link Panel} has them send, as the real controller does. It makes no sound; what it sends goes to {@link Out}, at
 * once, on whatever thread worked it (the game's, a MIDI device's, the arpeggiator's clock).
 */
public final class Controller {
    /**
     * Messages that are no MIDI, in the same shape (see {@link #message}): a key or pad itself going down (with its
     * velocity) or up (0), for those who watch.
     */
    public static final int KEY = 0x01, PAD = 0x02;
    public static final int NOTE_OFF = 0x80, NOTE_ON = 0x90, CONTROL = 0xB0, PROGRAM = 0xC0, PRESSURE = 0xD0, BEND = 0xE0;
    public static final int DRUM_CHANNEL = 9;
    /** Snapshot modes: picking one to recall, or a place to write one. */
    public static final int RECALL = 1, WRITE = 2;
    /** The keys that work a function while FUNC is lit, counted from the lowest: as printed above them. */
    public static final int PANIC_KEY = 0, FIRST_CHANNEL_KEY = 1, FIRST_STEP_KEY = 27, FIRST_GATE_KEY = 33;
    /** How hard each velocity curve makes a touch count, SUPER LIGHT to SUPER HEAVY. */
    private static final double[] CURVES = {0.5, 0.7, 1, 1.3, 1.7, 2.2};
    /** An arpeggiator step in beats: 1/4, 1/4T, 1/8, 1/8T, 1/16, 1/16T. */
    private static final double[] STEP_BEATS = {1, 2 / 3.0, 1 / 2.0, 1 / 3.0, 1 / 4.0, 1 / 6.0};
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CMS arpeggiator");
        thread.setDaemon(true);
        return thread;
    });

    /** Where what it sends goes. */
    public interface Out {
        /** A message to play and pass on (see {@link Controller#message}). */
        void send(int message);

        /** Its panel was set differently. */
        void panelChanged();
    }

    private final Panel panel;
    private final Out out;
    private boolean shift, func, transposing, pickingSplit;
    private int snapshotMode, snapshotPad = -1;
    /** What each key held sounds, as the note-offs that end it. */
    private final Map<Integer, List<Integer>> sounding = new HashMap<>();
    private final int[] padSounding = new int[Panel.PADS];
    /** The arpeggiator's keys held (by their note), and the notes it runs over: {pitch, velocity}. */
    private final Map<Integer, int[]> arpeggioHeld = new LinkedHashMap<>();
    private final List<int[]> arpeggioNotes = new ArrayList<>();
    private int arpeggioAt, arpeggioSounding = -1, arpeggioStep;
    private boolean stepping;
    private long nextStep, lastTap;

    public Controller(Panel panel, Out out) {
        this.panel = panel;
        this.out = out;
    }

    public static int message(int status, int channel, int data1, int data2) {
        return status | channel & 15 | (data1 & 127) << 8 | (data2 & 127) << 16;
    }

    public Panel panel() {
        return panel;
    }

    public synchronized boolean lit(Control control) {
        return switch (control) {
            case SHIFT -> shift;
            case FUNC -> func;
            case TRANSPOSE -> transposing;
            case SPLIT -> panel.split || pickingSplit;
            case SNAPSHOT -> snapshotMode != 0;
            default -> false;
        };
    }

    public synchronized int snapshotMode() {
        return snapshotMode;
    }

    public synchronized int snapshotPad() {
        return snapshotPad;
    }

    /** Its programs as its panel has them: sent as its player sits down, so every listener's sound matches. */
    public synchronized void sendPrograms() {
        for (int zone = 0; zone < 3; zone++) {
            out.send(message(PROGRAM, panel.channel[zone], panel.program[zone], 0));
        }
    }

    /** The key of {@code note} went down at {@code velocity} (1..127), or up (0). */
    public synchronized void key(int note, int velocity) {
        if (velocity > 0 && func) {
            func = false;
            function(note - Layout.LOWEST);
            return;
        }
        if (velocity > 0 && pickingSplit) {
            pickingSplit = false;
            releaseKeys();
            panel.split = true;
            panel.splitPoint = note;
            out.panelChanged();
            return;
        }
        out.send(message(KEY, 0, note, velocity));
        List<Integer> ends = sounding.remove(note);
        if (ends != null) {
            ends.forEach(out::send);
        }
        if (arpeggioHeld.remove(note) != null && !panel.hold) {
            arpeggioNotes.removeIf(held -> held[2] == note);
            if (arpeggioNotes.isEmpty()) {
                arpeggioSilence();
            }
        }
        if (velocity == 0) {
            return;
        }
        int struck = Math.clamp(Math.round(127 * Math.pow(velocity / 127.0, CURVES[panel.curve])), 1, 127);
        int pitch = note + 12 * panel.octave + panel.transpose;
        ends = new ArrayList<>(3);
        for (int zone = 0; zone < 3 && pitch >= 0 && pitch <= 127; zone++) {
            if (!panel.plays(zone, note)) {
                continue;
            }
            if (zone == Panel.UPPER_1 && panel.arpeggio) {
                // Held: the first key after all were let go starts a new chord.
                if (arpeggioHeld.isEmpty() && panel.hold) {
                    arpeggioNotes.clear();
                }
                arpeggioHeld.put(note, new int[]{pitch, struck, note});
                arpeggioNotes.removeIf(held -> held[0] == pitch);
                arpeggioNotes.add(new int[]{pitch, struck, note});
                if (!stepping) {
                    stepping = true;
                    arpeggioAt = 0;
                    nextStep = System.nanoTime();
                    CLOCK.execute(this::arpeggioStep);
                }
            } else {
                out.send(message(NOTE_ON, panel.channel[zone], pitch, struck));
                ends.add(message(NOTE_OFF, panel.channel[zone], pitch, 0));
            }
        }
        sounding.put(note, ends);
    }

    private synchronized void arpeggioStep() {
        int step = ++arpeggioStep;
        arpeggioSilence();
        if (arpeggioNotes.isEmpty() || !panel.arpeggio) {
            stepping = false;
            return;
        }
        List<int[]> notes = new ArrayList<>(arpeggioNotes);
        notes.sort((a, b) -> a[0] - b[0]);
        int count = notes.size() * panel.range;
        // Up and down turns round at each end without sounding it twice.
        int cycle = panel.order == 2 && count > 1 ? 2 * count - 2 : count;
        int at = panel.order == 3 ? ThreadLocalRandom.current().nextInt(count) : arpeggioAt++ % cycle;
        if (panel.order == 1) {
            at = count - 1 - at;
        } else if (at >= count) {
            at = cycle - at;
        }
        int[] note = notes.get(at % notes.size());
        int pitch = note[0] + 12 * (at / notes.size());
        if (pitch <= 127) {
            arpeggioSounding = message(NOTE_OFF, panel.channel[Panel.UPPER_1], pitch, 0);
            out.send(message(NOTE_ON, panel.channel[Panel.UPPER_1], pitch, note[1]));
        }
        long length = (long) (60e9 / panel.tempo * STEP_BEATS[panel.step]);
        if (panel.gate == 0) {
            CLOCK.schedule(() -> {
                synchronized (this) {
                    if (arpeggioStep == step) {
                        arpeggioSilence();
                    }
                }
            }, length / 2, TimeUnit.NANOSECONDS);
        }
        // Each step from where the last one was due, so the tempo holds however late a step ran.
        nextStep = Math.max(nextStep + length, System.nanoTime() - length);
        CLOCK.schedule(this::arpeggioStep, Math.max(0, nextStep - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private void arpeggioSilence() {
        if (arpeggioSounding >= 0) {
            out.send(arpeggioSounding);
            arpeggioSounding = -1;
        }
    }

    /** Pad {@code pad} (from 0) was struck at {@code velocity}, or let go (0). */
    public synchronized void pad(int pad, int velocity) {
        out.send(message(PAD, 0, pad, velocity));
        if (padSounding[pad] != 0) {
            out.send(padSounding[pad]);
            padSounding[pad] = 0;
        }
        if (velocity == 0) {
            return;
        }
        int channel = panel.channel[panel.target];
        if (snapshotMode != 0) {
            snapshotPad = pad;
        } else if (panel.arpeggio) {
            if (pad < 4) {
                panel.range = pad + 1;
            } else {
                panel.order = pad - 4;
            }
            out.panelChanged();
        } else if (panel.padMode == Panel.PROGRAMS) {
            panel.program[panel.target] = panel.bank() * Panel.PADS + pad;
            out.send(message(PROGRAM, channel, panel.program[panel.target], 0));
            out.panelChanged();
        } else if (panel.padMode == Panel.CONTROLS) {
            panel.latched[panel.bank()] ^= 1 << pad;
            out.send(message(CONTROL, channel, Panel.padController(panel.bank(), pad),
                    (panel.latched[panel.bank()] >> pad & 1) * 127));
            out.panelChanged();
        } else {
            int drum = Panel.drum(panel.bank(), pad);
            out.send(message(NOTE_ON, DRUM_CHANNEL, drum, velocity));
            padSounding[pad] = message(NOTE_OFF, DRUM_CHANNEL, drum, 0);
        }
    }

    /** Knob {@code knob} (from 0) was turned to {@code value} (0..127). */
    public synchronized void knob(int knob, int value) {
        if (panel.knob[knob] != value) {
            panel.knob[knob] = value;
            out.send(message(CONTROL, panel.channel[panel.target], Panel.KNOB_CONTROLLERS[knob], value));
            out.panelChanged();
        }
    }

    /** A controller that goes to every zone that sounds: a pedal, the lever's modulation. */
    public synchronized void control(int controller, int value) {
        everyZone(CONTROL, controller, value);
    }

    /** The lever's bend, or a pitch wheel's: 0..16383, 8192 at rest. */
    public synchronized void bend(int value) {
        everyZone(BEND, value & 127, value >> 7);
    }

    public synchronized void pressure(int value) {
        everyZone(PRESSURE, value, 0);
    }

    private void everyZone(int status, int data1, int data2) {
        int sent = 0;
        for (int zone = 0; zone < 3; zone++) {
            if (panel.sounds(zone) && (sent >> panel.channel[zone] & 1) == 0) {
                sent |= 1 << panel.channel[zone];
                out.send(message(status, panel.channel[zone], data1, data2));
            }
        }
    }

    /** A button was pressed. */
    public synchronized void press(Control control) {
        switch (control) {
            case BANK_DOWN, BANK_UP -> panel.bank[panel.padMode] = panel.bank() + (control == Control.BANK_UP ? 1 : Panel.BANKS - 1)
                    & Panel.BANKS - 1;
            case SNAPSHOT -> {
                snapshotMode = snapshotMode != 0 ? 0 : shift ? WRITE : RECALL;
                snapshotPad = -1;
                shift = false;
            }
            case PREV -> snapshotMode = 0;
            case NEXT -> {
                if (snapshotMode == WRITE && snapshotPad >= 0) {
                    panel.snapshots[snapshotPad] = panel.snapshot();
                } else if (snapshotMode == RECALL && snapshotPad >= 0 && panel.snapshots[snapshotPad] != null) {
                    releaseKeys();
                    panel.restore(panel.snapshots[snapshotPad]);
                    sendPrograms();
                    for (int knob = 0; knob < Panel.KNOBS; knob++) {
                        out.send(message(CONTROL, panel.channel[panel.target], Panel.KNOB_CONTROLLERS[knob], panel.knob[knob]));
                    }
                }
                snapshotMode = 0;
            }
            case PROGRAM -> panel.padMode = Panel.PROGRAMS;
            case CC -> panel.padMode = Panel.CONTROLS;
            case NOTE -> panel.padMode = Panel.NOTES;
            case SHIFT -> shift = !shift;
            case FUNC -> func = !func;
            case TRANSPOSE -> transposing = !transposing;
            case LOWER, UPPER_1, UPPER_2 -> zone(control.index());
            case SPLIT -> {
                releaseKeys();
                if (shift) {
                    pickingSplit = !pickingSplit;
                    shift = false;
                } else {
                    panel.split = !panel.split && !pickingSplit;
                    pickingSplit = false;
                }
            }
            case S1 -> {
                if (panel.arpeggio) {
                    panel.hold = !panel.hold;
                    if (!panel.hold) {
                        arpeggioNotes.removeIf(note -> !arpeggioHeld.containsKey(note[2]));
                    }
                } else {
                    out.send(message(CONTROL, panel.channel[panel.target], 80, 127));
                }
            }
            case S2 -> {
                if (panel.arpeggio) {
                    long now = System.nanoTime(), gap = now - lastTap;
                    lastTap = now;
                    if (gap < 2_000_000_000L) {
                        panel.tempo = Math.clamp(Math.round(60e9 / gap), Panel.MIN_TEMPO, Panel.MAX_TEMPO);
                    }
                } else {
                    out.send(message(CONTROL, panel.channel[panel.target], 81, 127));
                }
            }
            case OCTAVE_DOWN, OCTAVE_UP -> {
                int by = control == Control.OCTAVE_UP ? 1 : -1;
                releaseKeys();
                if (transposing) {
                    panel.transpose = Math.clamp(panel.transpose + by, Panel.MIN_TRANSPOSE, Panel.MAX_TRANSPOSE);
                } else if (shift) {
                    panel.curve = Math.clamp(panel.curve + by, 0, Panel.CURVES - 1);
                } else {
                    panel.octave = Math.clamp(panel.octave + by, Panel.MIN_OCTAVE, Panel.MAX_OCTAVE);
                }
            }
            default -> {
            }
        }
        out.panelChanged();
    }

    /** A button was let go: S1 and S2 send their controller only while held. */
    public synchronized void release(Control control) {
        if ((control == Control.S1 || control == Control.S2) && !panel.arpeggio) {
            out.send(message(CONTROL, panel.channel[panel.target], control == Control.S1 ? 80 : 81, 0));
        }
    }

    /**
     * A zone's button: with SHIFT, UPPER 1's turns the arpeggiator on or off. Else a zone that is off comes on, one
     * that is on becomes the one the pads and knobs work, and that one, pressed again, goes off (the last stays on).
     */
    private void zone(int zone) {
        releaseKeys();
        if (shift && zone == Panel.UPPER_1) {
            shift = false;
            panel.arpeggio = !panel.arpeggio;
            arpeggioHeld.clear();
            arpeggioNotes.clear();
            arpeggioSilence();
        } else if (!panel.sounds(zone) || panel.target != zone) {
            panel.zones |= 1 << zone;
            panel.target = zone;
        } else if (panel.zones != 1 << zone) {
            panel.zones &= ~(1 << zone);
            panel.target = Integer.numberOfTrailingZeros(panel.zones & 2) == 1 ? Panel.UPPER_1
                    : Integer.numberOfTrailingZeros(panel.zones);
        }
    }

    /** What the key {@code key} from the lowest does while FUNC is lit. */
    private void function(int key) {
        if (key == PANIC_KEY) {
            panic();
        } else if (key >= FIRST_CHANNEL_KEY && key < FIRST_CHANNEL_KEY + 16) {
            releaseKeys();
            panel.channel[panel.target] = key - FIRST_CHANNEL_KEY;
            out.send(message(PROGRAM, panel.channel[panel.target], panel.program[panel.target], 0));
        } else if (key >= FIRST_STEP_KEY && key < FIRST_STEP_KEY + STEP_BEATS.length) {
            panel.step = key - FIRST_STEP_KEY;
        } else if (key >= FIRST_GATE_KEY && key < FIRST_GATE_KEY + 2) {
            panel.gate = key - FIRST_GATE_KEY;
        }
        out.panelChanged();
    }

    /** Every sound off on every channel, and its controllers as they start. */
    public synchronized void panic() {
        releaseKeys();
        for (int channel = 0; channel < 16; channel++) {
            out.send(message(CONTROL, channel, 120, 0));
            out.send(message(CONTROL, channel, 123, 0));
            out.send(message(CONTROL, channel, 121, 0));
        }
    }

    /** Lets go every key and pad held, and what the arpeggiator runs over: before its panel changes what they play, or as its player leaves. */
    public synchronized void releaseKeys() {
        sounding.forEach((note, ends) -> {
            out.send(message(KEY, 0, note, 0));
            ends.forEach(out::send);
        });
        sounding.clear();
        for (int pad = 0; pad < padSounding.length; pad++) {
            if (padSounding[pad] != 0) {
                out.send(padSounding[pad]);
                padSounding[pad] = 0;
            }
        }
        arpeggioHeld.clear();
        arpeggioNotes.clear();
        arpeggioSilence();
    }
}
