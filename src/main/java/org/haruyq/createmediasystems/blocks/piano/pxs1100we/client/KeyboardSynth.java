package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardSettings.Tone;
import org.haruyq.gervill.SoftSynthesizer;
import org.haruyq.gervill.ModelPatch;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;

/** One stereo SoundFont synth for one instrument, advanced by its two OpenAL sources. */
final class KeyboardSynth implements AutoCloseable {
    static final int RATE = 48_000;
    private static final int VOICES_PER_INPUT = 3, CHANNELS = 16 * VOICES_PER_INPUT + 1;
    private static final int MAIN = 0, LAYER = 1, LOWER = 2, CLICK_CHANNEL = 9;
    private static final int PERCUSSION_BANK = 120 << 7;
    private static final Tone KIT = new Tone(KeyboardSettings.DRUM_BANK, 0);

    private final SoftSynthesizer synth = new SoftSynthesizer();
    private final AudioInputStream pcm;
    private final MidiChannel[] outputs;
    private final KeyboardSoundbank.Bank bank;
    private final Instrument[] patches = new Instrument[CHANNELS];
    private final Tone[] midiTones = new Tone[16];
    private final Map<Instrument, Boolean> loaded = new LinkedHashMap<>(32, .75f, true);
    private final int[] banks = new int[16], soft = new int[16];
    private final int[][] notes = new int[16][128], counts = new int[CHANNELS][128];
    private KeyboardSettings settings = new KeyboardSettings();
    private int framesToReleaseClick, clickNote;

    KeyboardSynth(KeyboardSoundbank.Bank bank, int polyphony) {
        this.bank = bank;
        try {
            pcm = synth.openStream(new AudioFormat(RATE, 16, 2, true, false), Map.of(
                    "load default soundbank", false, "max polyphony", polyphony, "midi channels", CHANNELS,
                    "interpolation", "linear", "auto gain control", false,
                    "large mode", bank.name().equals(KeyboardSettings.DEFAULT_FONT)));
            outputs = synth.getChannels();
            select(CLICK_CHANNEL, KIT);
            select(0, settings.main);
        } catch (MidiUnavailableException | RuntimeException e) {
            synth.close();
            throw new IllegalStateException("Could not start SoundFont synth", e);
        }
    }

    void apply(KeyboardSettings next, boolean snapshot, List<KeyboardMidi> events) {
        if (snapshot) {
            for (int input = 0; input < 16; input++) {
                control(input, SILENCE, 0);
                control(input, RESET, 0);
            }
            Arrays.fill(banks, 0);
            Arrays.fill(midiTones, null);
            outputs[CLICK_CHANNEL].allSoundOff();
        }
        if (snapshot || settings.reverb != next.reverb || settings.chorus != next.chorus) {
            for (var channel : outputs) {
                channel.controlChange(91, next.reverb);
                channel.controlChange(93, next.chorus);
            }
        }
        settings = next;
        for (var event : events) event(event);
    }

    private void event(KeyboardMidi event) {
        int input = event.channel(), key = event.key(), value = event.value();
        switch (event.command()) {
            case ON -> {
                if (value == 0) noteOff(input, key, 0);
                else noteOn(input, key, value);
            }
            case OFF -> noteOff(input, key, value);
            case CONTROL -> {
                if (event.metronome()) {
                    var click = outputs[CLICK_CHANNEL];
                    click.noteOff(clickNote);
                    clickNote = value == 2 ? 76 : 77;
                    click.noteOn(clickNote, value == 2 ? 100 : 79);
                    framesToReleaseClick = RATE / 10;
                } else if (key == 0) banks[input] = value << 7 | banks[input] & 127;
                else if (key == 32) banks[input] = banks[input] & ~127 | value;
                else if (key != 7) control(input, key, value);
            }
            case PROGRAM -> {
                if (input != 0) midiTones[input] = new Tone(input == 9 ? KeyboardSettings.DRUM_BANK : banks[input], key);
            }
            case BEND -> {
                for (int voice = 0; voice < VOICES_PER_INPUT; voice++) outputs[route(input, voice)].setPitchBend(event.bend());
            }
        }
    }

    private void noteOn(int input, int key, int velocity) {
        if (notes[input][key] != 0) noteOff(input, key, 0);
        int actual = Math.clamp(key + settings.transpose, 0, 127);
        int gain = switch (settings.touch) {
            case 0 -> 96;
            case 1 -> Math.round((float) Math.sqrt(velocity / 127f) * 127);
            case 3 -> Math.max(1, velocity * velocity / 127);
            case 4 -> Math.max(1, (int) Math.round(Math.pow(velocity / 127.0, 3) * 127));
            default -> velocity;
        };
        gain = Math.max(1, gain - gain * soft[input] / (127 * 4));
        if (input == 9) {
            hit(input, key, MAIN, key, gain, midiTones[input] == null ? KIT : midiTones[input]);
        } else if (settings.split && key < settings.splitKey) {
            hit(input, key, LOWER, actual, gain, settings.lower);
        } else {
            var tone = midiTones[input] == null ? settings.main : midiTones[input];
            hit(input, key, MAIN, actual, gain, tone);
            if (settings.layered) hit(input, key, LAYER, actual, Math.max(1, gain * 3 / 4), settings.layer);
        }
    }

    private void hit(int input, int key, int voice, int actual, int gain, Tone tone) {
        int index = route(input, voice);
        select(index, tone);
        notes[input][key] |= (actual + 1) << (8 * voice);
        counts[index][actual]++;
        outputs[index].noteOn(actual, gain);
    }

    private void select(int index, Tone tone) {
        var instrument = bank.instruments().get(tone);
        if (instrument == null) {
            boolean drums = tone.bank() == KeyboardSettings.DRUM_BANK;
            var fallback = drums ? KIT : new Tone(0, tone.program());
            instrument = bank.instruments().get(fallback);
            if (instrument == null) instrument = KeyboardSoundbank.bank(KeyboardSettings.DEFAULT_FONT).join().instruments().get(fallback);
        }
        if (instrument == null) return;
        if (loaded.put(instrument, true) == null) {
            // Several instruments can share the same file-backed sample buffers.
            synchronized (bank) {
                if (!synth.loadInstrument(instrument)) {
                    loaded.remove(instrument);
                    return;
                }
            }
            if (loaded.size() > 32) {
                var iterator = loaded.keySet().iterator();
                synth.unloadInstrument(iterator.next());
                iterator.remove();
            }
        }
        if (patches[index] != instrument) {
            var patch = instrument.getPatch();
            boolean percussion = patch instanceof ModelPatch model && model.isPercussion();
            int selectedBank = percussion && index != CLICK_CHANNEL ? PERCUSSION_BANK : patch.getBank();
            outputs[index].programChange(selectedBank, patch.getProgram());
            patches[index] = instrument;
        }
    }

    private void noteOff(int input, int key, int velocity) {
        int routes = notes[input][key];
        notes[input][key] = 0;
        for (int voice = 0; voice < VOICES_PER_INPUT; voice++) {
            int actual = (routes >>> (8 * voice) & 255) - 1;
            int index = route(input, voice);
            if (actual >= 0 && --counts[index][actual] == 0) outputs[index].noteOff(actual, velocity);
        }
    }

    private void control(int input, int cc, int value) {
        boolean stopsNotes = cc == SILENCE || cc == NOTES_OFF;
        if (stopsNotes) Arrays.fill(notes[input], 0);
        if (cc == SOFT) soft[input] = value;
        else if (cc == RESET) soft[input] = 0;
        for (int voice = 0; voice < VOICES_PER_INPUT; voice++) {
            int index = route(input, voice);
            if (stopsNotes) Arrays.fill(counts[index], 0);
            if (cc == SUSTAIN) outputs[index].controlChange(72, 64 + Math.min(value, 63) / 4);
            if (cc == SOFT) outputs[index].controlChange(74, 64 - value / 9);
            outputs[index].controlChange(cc, value);
            if (cc == RESET) {
                outputs[index].setPitchBend(8192);
                outputs[index].controlChange(91, settings.reverb);
                outputs[index].controlChange(93, settings.chorus);
            }
        }
    }

    private static int route(int input, int voice) {
        int index = input * VOICES_PER_INPUT + voice;
        return index >= CLICK_CHANNEL ? index + 1 : index;
    }

    void read(byte[] stereo) throws IOException {
        if (framesToReleaseClick > 0 && (framesToReleaseClick -= stereo.length / 4) <= 0) outputs[CLICK_CHANNEL].noteOff(clickNote);
        int size = pcm.readNBytes(stereo, 0, stereo.length);
        if (size < stereo.length) Arrays.fill(stereo, size, stereo.length, (byte) 0);
    }

    @Override public void close() { synth.close(); }
}
