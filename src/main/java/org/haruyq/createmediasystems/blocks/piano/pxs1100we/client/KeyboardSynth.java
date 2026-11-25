package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings.Tone;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;

/** One stereo SoundFont synth for one instrument, advanced by its two OpenAL sources. */
public final class KeyboardSynth {
    static final int RATE = Synth.RATE;
    public static final int FRAMES_PER_BUFFER = 8 * Synth.BLOCK;
    private static final int VOICES_PER_INPUT = 3, CHANNELS = 16 * VOICES_PER_INPUT + 1;
    private static final int MAIN = 0, LAYER = 1, LOWER = 2, CLICK_CHANNEL = Synth.DRUM_CHANNEL;
    private static final int MIN_ATTACK_FRAMES = RATE * 15 / 1000;
    private static final Tone KIT = new Tone(KeyboardSettings.DRUM_BANK, 0);
    /** The bank's tones at about the level they had before, on average. */
    private static final float LEVEL = .77f;
    /** The bundled piano: one strike's regions, louder and brighter with its velocity than the bank's own curves. */
    private static final int PIANO_REGIONS = 75;
    /** Strikes (dB) as loud as the piano was before, which sounded at half scale. */
    private static final float PIANO_TRIM = (float) (20 * Math.log10(.5 / LEVEL));
    private static final double MIN_GAIN = .16, BRIGHTNESS_RANGE = 840;

    private record Batch(KeyboardSettings settings, boolean snapshot, List<KeyboardMidi> events) {}

    private final Synth synth;
    /** The bundled bank, for the tones a custom bank lacks, as before; null when this one is the bundled bank. */
    private final Synth fallback;
    private final SoundFont fallbackFont;
    private final KeyboardSoundbank.Bank bank;
    private final Tone[] midiTones = new Tone[16];
    private final Tone[] selected = new Tone[CHANNELS];
    private final boolean[] piano = new boolean[CHANNELS], borrowed = new boolean[CHANNELS];
    private final int[] banks = new int[16], soft = new int[16], velocityPrefixes = new int[16];
    private final int[][] notes = new int[16][128], counts = new int[CHANNELS][128];
    private final ArrayDeque<Batch> batches = new ArrayDeque<>();
    private final long[] releaseAfter = new long[16 * 128];
    private float[] left = new float[0], right = new float[0], fallbackLeft = left, fallbackRight = left;
    private Iterator<KeyboardMidi> events = Collections.emptyIterator();
    private KeyboardMidi nextAfterAttack;
    private boolean restoring;
    private int attackFrames;
    private long renderedFrames;
    private KeyboardSettings settings = new KeyboardSettings();
    private int framesToReleaseClick, clickNote;

    KeyboardSynth(KeyboardSoundbank.Bank bank, int polyphony) {
        this.bank = bank;
        synth = new Synth(bank.font(), polyphony, CHANNELS);
        fallbackFont = bank.name().equals(KeyboardSettings.DEFAULT_FONT) ? null : SoundFont.bundled().exceptionally(e -> null).join();
        fallback = fallbackFont == null ? null : new Synth(fallbackFont, polyphony, CHANNELS);
        select(CLICK_CHANNEL, KIT);
        select(0, settings.main);
    }

    public void apply(KeyboardSettings next, boolean snapshot, List<KeyboardMidi> events) {
        if (snapshot) {
            batches.clear();
            this.events = Collections.emptyIterator();
            nextAfterAttack = null;
            Arrays.fill(releaseAfter, 0);
            attackFrames = 0;
        }
        batches.addLast(new Batch(next, snapshot, events));
    }

    /** Decodes the tones of {@code next} ahead, so their first notes don't wait for it. Any thread. */
    void prepare(KeyboardSettings next) {
        for (var tone : new Tone[] {next.main, next.layer, next.lower}) {
            var font = font(tone);
            font.prepare(font.preset(fontBank(bankSelect(font, tone)), tone.program()));
        }
    }

    int queuedBatches() {
        return batches.size() + (events.hasNext() || nextAfterAttack != null ? 1 : 0);
    }

    private void send(int status, int channel, int data1, int data2) {
        synth.send(Synth.message(status, channel, data1, data2));
        if (fallback != null) fallback.send(Synth.message(status, channel, data1, data2));
    }

    private Synth synth(int channel) {
        return borrowed[channel] ? fallback : synth;
    }

    private void configure(KeyboardSettings next, boolean snapshot) {
        if (snapshot) {
            for (int input = 0; input < 16; input++) {
                control(input, SILENCE, 0);
                control(input, RESET, 0);
            }
            Arrays.fill(banks, 0);
            Arrays.fill(midiTones, null);
            send(Synth.CONTROL, CLICK_CHANNEL, Synth.ALL_SOUND_OFF, 0);
            framesToReleaseClick = 0;
        }
        if (snapshot || settings.reverb != next.reverb || settings.chorus != next.chorus) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                send(Synth.CONTROL, channel, Synth.REVERB_SEND, next.reverb);
                send(Synth.CONTROL, channel, Synth.CHORUS_SEND, next.chorus);
            }
        }
        settings = next;
    }

    private void processEvents() {
        if (nextAfterAttack != null) {
            event(nextAfterAttack);
            nextAfterAttack = null;
        }
        while (true) {
            while (!events.hasNext()) {
                var batch = batches.pollFirst();
                if (batch == null) return;
                restoring = batch.snapshot();
                configure(batch.settings(), restoring);
                events = batch.events().iterator();
            }
            var next = events.next();
            long until = releaseAfter[next.channel() * 128 + next.key()];
            if (!restoring && (next.command() == ON || next.command() == OFF) && until > renderedFrames) {
                // A release or repeated strike waits until the current attack has sounded.
                nextAfterAttack = next;
                attackFrames = (int) (until - renderedFrames);
                return;
            }
            event(next);
        }
    }

    private void event(KeyboardMidi event) {
        int input = event.channel(), key = event.key(), value = event.value();
        int fineVelocity = velocityPrefixes[input];
        velocityPrefixes[input] = 0;
        switch (event.command()) {
            case ON -> {
                if (value == 0) noteOff(input, key, 0);
                else {
                    noteOn(input, key, Math.min(127, value + fineVelocity / 128.0));
                    releaseAfter[input * 128 + key] = renderedFrames + MIN_ATTACK_FRAMES;
                }
            }
            case OFF -> noteOff(input, key, value);
            case CONTROL -> {
                if (event.metronome()) {
                    send(Synth.NOTE_OFF, CLICK_CHANNEL, clickNote, 0);
                    clickNote = value == 2 ? 76 : 77;
                    // Each wood block at the level it had against the piano before.
                    int velocity = value == 2 ? 100 : 79;
                    synth(CLICK_CHANNEL).strike(Synth.message(Synth.NOTE_ON, CLICK_CHANNEL, clickNote, velocity), velocity,
                            (value == 2 ? 9.4f : 2.6f) + PIANO_TRIM, 0);
                    framesToReleaseClick = RATE / 10;
                } else if (key == VELOCITY_PREFIX) velocityPrefixes[input] = value;
                else if (key == 0) banks[input] = value << 7 | banks[input] & 127;
                else if (key == 32) banks[input] = banks[input] & ~127 | value;
                else if (key != 7) control(input, key, value);
            }
            case PROGRAM -> {
                if (input != 0) midiTones[input] = Tone.fromMidiBank(banks[input], key);
            }
            case BEND -> {
                for (int voice = 0; voice < VOICES_PER_INPUT; voice++) {
                    send(Synth.BEND, route(input, voice), event.bend() & 127, event.bend() >> 7);
                }
            }
        }
    }

    private void noteOn(int input, int key, double velocity) {
        if (notes[input][key] != 0) noteOff(input, key, 0);
        int actual = Math.clamp(key + settings.transpose, 0, 127);
        double gain = Math.max(1, touchResponse(velocity, settings.touch) * (1 - soft[input] / (127.0 * 4)));
        if (settings.split && key < settings.splitKey) {
            hit(input, key, LOWER, actual, gain, settings.lower);
        } else {
            var tone = midiTones[input] == null ? settings.main : midiTones[input];
            hit(input, key, MAIN, actual, gain, tone);
            if (settings.layered) hit(input, key, LAYER, actual, Math.max(1, gain * 3 / 4), settings.layer);
        }
    }

    private static double touchResponse(double velocity, int touch) {
        if (touch == 0) return 96;
        double position = (velocity - 1) / 126.0;
        double curve = switch (touch) {
            case 1 -> .65;
            case 3 -> -.45;
            case 4 -> -.8;
            default -> 0;
        };
        return 1 + 126 * (position + curve * position * (1 - position));
    }

    private void hit(int input, int key, int voice, int actual, double gain, Tone tone) {
        int index = route(input, voice);
        select(index, tone);
        if (tone.bank() == KeyboardSettings.DRUM_BANK) actual = key;
        notes[input][key] |= (actual + 1) << (8 * voice);
        counts[index][actual]++;
        int message = Synth.message(Synth.NOTE_ON, index, actual, (int) Math.round(gain));
        if (piano[index]) {
            double touch = gain / 127;
            synth(index).strike(message, PIANO_REGIONS, (float) (20 * Math.log10(MIN_GAIN + (1 - MIN_GAIN) * touch)) + PIANO_TRIM,
                    (float) (BRIGHTNESS_RANGE * touch));
        } else {
            synth(index).send(message);
        }
    }

    /**
     * The bank select (MSB) that plays {@code tone}. A saved tone's bank is the MIDI bank of the SoundFont's, but
     * {@link KeyboardSettings#DRUM_BANK} for both the drum kits and the bank-1 variations; the variation wins.
     */
    private static int bankSelect(SoundFont font, Tone tone) {
        if (tone.bank() == KeyboardSettings.DRUM_BANK) return font.name(1, tone.program()) != null ? 1 : 120;
        return (tone.bank() & 127) == 0 ? tone.bank() >> 7 : 0;
    }

    private static int fontBank(int bankSelect) {
        return bankSelect == 120 ? SoundFont.DRUMS : bankSelect;
    }

    /** The bank that plays {@code tone}: this one, else the bundled one if this one lacks it and its bank-0 program (or kit). */
    private SoundFont font(Tone tone) {
        var font = bank.font();
        boolean drums = tone.bank() == KeyboardSettings.DRUM_BANK;
        return fallbackFont == null || font.name(fontBank(bankSelect(font, tone)), tone.program()) != null
                || font.name(drums ? SoundFont.DRUMS : 0, drums ? 0 : tone.program()) != null ? font : fallbackFont;
    }

    private void select(int index, Tone tone) {
        if (tone.equals(selected[index])) return;
        selected[index] = tone;
        var font = font(tone);
        borrowed[index] = font != bank.font();
        int msb = bankSelect(font, tone);
        // Only to the synth that plays it: a program change decodes its samples.
        synth(index).send(Synth.message(Synth.CONTROL, index, Synth.BANK, msb));
        synth(index).send(Synth.message(Synth.PROGRAM, index, tone.program(), 0));
        piano[index] = (borrowed[index] || bank.name().equals(KeyboardSettings.DEFAULT_FONT))
                && font.preset(fontBank(msb), tone.program()) == font.preset(0, 0);
    }

    private void noteOff(int input, int key, int velocity) {
        int routes = notes[input][key];
        notes[input][key] = 0;
        for (int voice = 0; voice < VOICES_PER_INPUT; voice++) {
            int actual = (routes >>> (8 * voice) & 255) - 1;
            int index = route(input, voice);
            if (actual >= 0 && --counts[index][actual] == 0) send(Synth.NOTE_OFF, index, actual, velocity);
        }
    }

    private void control(int input, int cc, int value) {
        boolean stopsNotes = cc == SILENCE || cc == NOTES_OFF;
        if (cc == RESET) velocityPrefixes[input] = 0;
        if (stopsNotes) {
            Arrays.fill(notes[input], 0);
            Arrays.fill(releaseAfter, input * 128, (input + 1) * 128, 0);
        }
        if (cc == SOFT) soft[input] = value;
        else if (cc == RESET) soft[input] = 0;
        for (int voice = 0; voice < VOICES_PER_INPUT; voice++) {
            int index = route(input, voice);
            if (stopsNotes) Arrays.fill(counts[index], 0);
            if (cc == SUSTAIN) send(Synth.CONTROL, index, Synth.RELEASE_TIME, 64 + Math.min(value, 63) / 4);
            // The soft pedal is this keyboard's own: quieter (see noteOn) and duller, not the synth's.
            if (cc == SOFT) send(Synth.CONTROL, index, Synth.BRIGHTNESS, 64 - value / 9);
            else send(Synth.CONTROL, index, cc, value);
            if (cc == RESET) {
                send(Synth.BEND, index, 0, 64);
                send(Synth.CONTROL, index, Synth.RELEASE_TIME, 64);
                send(Synth.CONTROL, index, Synth.BRIGHTNESS, 64);
                send(Synth.CONTROL, index, Synth.REVERB_SEND, settings.reverb);
                send(Synth.CONTROL, index, Synth.CHORUS_SEND, settings.chorus);
            }
        }
    }

    private static int route(int input, int voice) {
        int index = input * VOICES_PER_INPUT + voice;
        return index >= CLICK_CHANNEL ? index + 1 : index;
    }

    void read(byte[] stereo) {
        read(stereo, stereo.length / 4);
    }

    /** Renders {@code frames}, whole synth blocks, as 16-bit stereo. */
    public void read(byte[] stereo, int frames) {
        if (attackFrames == 0) processEvents();
        if (left.length < frames) {
            left = new float[frames];
            right = new float[frames];
            fallbackLeft = new float[frames];
            fallbackRight = new float[frames];
        }
        synth.render(left, right, 0, frames / Synth.BLOCK);
        if (fallback != null) fallback.render(fallbackLeft, fallbackRight, 0, frames / Synth.BLOCK);
        for (int i = 0; i < frames; i++) {
            put(stereo, i * 4, left[i] + fallbackLeft[i]);
            put(stereo, i * 4 + 2, right[i] + fallbackRight[i]);
        }
        renderedFrames += frames;
        if (attackFrames > 0) attackFrames = Math.max(0, attackFrames - frames);
        if (framesToReleaseClick > 0 && (framesToReleaseClick -= frames) <= 0) {
            send(Synth.NOTE_OFF, CLICK_CHANNEL, clickNote, 0);
        }
    }

    private static void put(byte[] stereo, int at, float sample) {
        int value = Math.round(Math.clamp(sample * LEVEL, -1, 1) * 32767);
        stereo[at] = (byte) value;
        stereo[at + 1] = (byte) (value >> 8);
    }
}
