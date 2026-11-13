package org.haruyq.createmediasystems.blocks.piano.grand.client;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.*;

/** Renders the recorded strings and the mechanical noises on the audio thread. */
final class PianoSynth {
    private static final int MAX_VOICES = 112;
    private static final int[] LAYER_VELOCITIES = {12, 37, 62, 86, 117};

    private final List<Voice> voices = new ArrayList<>();
    private final PianoState keys = new PianoState();
    private int pedalVariant;
    private float level;

    void accept(PianoEvent event, PianoSamples samples) {
        int note = event.number();
        if (event.kind() == Kind.ON) {
            strike(note, event.value(), samples);
        } else if (event.kind() == Kind.OFF && keys.pressed(note)) {
            float release = event.value() / (float) MAX_VELOCITY;
            for (Voice voice : voices) {
                if (voice.note == note) voice.release = release;
            }
            add(samples.release(note), -1, 1, .018f + .075f * release, .82f);
        } else if (event.kind() == Kind.CONTROL && event.number() == DAMPER) {
            boolean down = event.value() >= 64;
            if (down != (keys.pedal(DAMPER) >= 64)) {
                add(samples.pedal(down, 1 + (pedalVariant++ & 1)), -1, 1, .13f, .85f);
            }
        } else if (event.kind() == Kind.CONTROL && event.number() == ALL_SOUND_OFF) {
            voices.forEach(voice -> voice.fastRelease = true);
        }
        keys.apply(event);
    }

    private void strike(int note, int value, PianoSamples samples) {
        float soft = keys.pedal(SOFT) / 127f;
        float velocity = Math.max(1, 127 * value / (float) MAX_VELOCITY * (1 - .28f * soft));
        int root = PianoSamples.root(note);
        int layer = 0;
        while (layer + 1 < LAYER_VELOCITIES.length
                && velocity > (LAYER_VELOCITIES[layer] + LAYER_VELOCITIES[layer + 1]) / 2f) layer++;
        for (Voice voice : voices) {
            if (voice.note == note) voice.repeated = true;
        }
        float volume = (float) Math.pow(velocity / LAYER_VELOCITIES[layer], .85) * (1 - .20f * soft) * .75f;
        double pitch = Math.pow(2, (note - root) / 12d);
        add(samples.key(root, layer), note, pitch, volume, 1 - .64f * soft);
        float pedal = keys.pedal(DAMPER) / 127f;
        if (pedal > 0) {
            add(samples.resonance(root), note, pitch, pedal * volume * .13f, .77f);
        }
    }

    private void add(short[] sample, int note, double pitch, float gain, float tone) {
        if (sample == null || sample.length < 2) return;
        if (voices.size() >= MAX_VOICES) {
            int quietest = 0;
            for (int i = 1; i < voices.size(); i++) {
                if (voices.get(i).amplitude < voices.get(quietest).amplitude) quietest = i;
            }
            voices.remove(quietest);
        }
        voices.add(new Voice(sample, note, pitch, gain, tone));
    }

    void render(float[] left, float[] right, int frames, int rate, PianoAudio.Position position) {
        float next = keys.output() * position.gain();
        float step = (next - level) / frames;
        for (var iterator = voices.iterator(); iterator.hasNext(); ) {
            var voice = iterator.next();
            double seconds = releaseSeconds(voice);
            float damping = seconds == 0 ? 1 : (float) Math.exp(-1 / (seconds * rate));
            double pan = Math.clamp(position.pan() + (voice.note < 0 ? 0 : (voice.note - 64.5) / 44 * .15), -1, 1);
            float leftGain = (float) Math.cos((pan + 1) * Math.PI / 4);
            float rightGain = (float) Math.sin((pan + 1) * Math.PI / 4);
            float tone = voice.tone * (position.lidOpen() ? 1 : .66f);
            double advance = voice.pitch * PianoSamples.RATE / rate;
            float gain = level;
            for (int frame = 0; frame < frames && voice.index < voice.samples.length - 1; frame++) {
                int i = (int) voice.index;
                float sample = (float) (voice.samples[i] + (voice.samples[i + 1] - voice.samples[i]) * (voice.index - i)) / 32_768f;
                voice.index += advance;
                voice.filtered += tone * (sample - voice.filtered);
                voice.attack = Math.min(1, voice.attack + 640f / rate);
                float out = voice.filtered * voice.attack * voice.amplitude * gain;
                left[frame] += out * leftGain;
                right[frame] += out * rightGain;
                voice.amplitude *= damping;
                gain += step;
            }
            if (voice.index >= voice.samples.length - 1 || voice.amplitude < .00015f) iterator.remove();
        }
        level = next;
    }

    private double releaseSeconds(Voice voice) {
        if (voice.fastRelease) return .004;
        if (voice.repeated) return .055;
        if (voice.note < 0 || keys.rings(voice.note)) return 0;
        double half = Math.min(63, keys.pedal(DAMPER)) / 63d;
        return (.10 + .54 * half * half) * (1.15 - .40 * voice.release);
    }

    private static final class Voice {
        final short[] samples;
        final int note;
        final double pitch;
        final float tone;
        double index;
        float amplitude, attack, filtered, release = .5f;
        boolean repeated, fastRelease;

        Voice(short[] samples, int note, double pitch, float amplitude, float tone) {
            this.samples = samples;
            this.note = note;
            this.pitch = pitch;
            this.amplitude = amplitude;
            this.tone = tone;
        }
    }
}
