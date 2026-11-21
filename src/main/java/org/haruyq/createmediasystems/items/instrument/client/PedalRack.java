package org.haruyq.createmediasystems.items.instrument.client;

import static org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth.RATE;

import java.util.List;
import org.haruyq.createmediasystems.items.instrument.GuitarPatch;

/** One signal chain per performer, with independent filter, delay and envelope memory. */
final class PedalRack {
    private static final int CROSSFADE = RATE / 50;
    private static final double CONTROL = smoothing(.011);
    private List<GuitarPatch.Setting> settings;
    private Stage[] stages;
    private Stage[] previous;
    private int remaining;

    PedalRack(List<GuitarPatch.Setting> initial) {
        settings = initial;
        stages = createStages(initial);
    }

    void configure(List<GuitarPatch.Setting> next) {
        if (settings.equals(next)) return;
        if (sameOrder(next)) {
            for (int i = 0; i < stages.length; i++) stages[i].setAmount(next.get(i).amount());
        } else {
            previous = stages;
            stages = createStages(next);
            remaining = CROSSFADE;
        }
        settings = next;
    }

    private boolean sameOrder(List<GuitarPatch.Setting> next) {
        if (settings.size() != next.size()) return false;
        for (int i = 0; i < stages.length; i++) if (!settings.get(i).link().equals(next.get(i).link())) return false;
        return true;
    }

    private static Stage[] createStages(List<GuitarPatch.Setting> settings) {
        return settings.stream().map(Stage::new).toArray(Stage[]::new);
    }

    double next(double input) {
        double sample = through(stages, input);
        if (remaining == 0) return sample;
        double old = through(previous, input);
        double result = mix(sample, old, remaining / (double) CROSSFADE);
        if (--remaining == 0) previous = null;
        return result;
    }

    private static double through(Stage[] stages, double sample) {
        for (Stage stage : stages) sample = stage.next(sample);
        return sample;
    }

    private static double mix(double dry, double wet, double amount) {
        return dry + amount * (wet - dry);
    }

    private static double smoothing(double seconds) {
        return -Math.expm1(-1 / (seconds * RATE));
    }

    private static double follow(double state, double target, double attack, double release) {
        return mix(state, target, target > state ? attack : release);
    }

    private static final class Stage {
        private final Effect effect;
        private double wet, depth, wetTarget, depthTarget = 2.0 / 3;

        Stage(GuitarPatch.Setting setting) {
            effect = switch (setting.link().effect()) {
                case WAH -> new Wah();
                case TREMOLO -> new Tremolo();
                case CHORUS -> new Chorus();
                case COMPRESSOR -> new Compressor();
            };
            setAmount(setting.amount());
            wet = wetTarget;
            depth = depthTarget;
        }

        void setAmount(int amount) {
            wetTarget = amount > 0 ? 1 : 0;
            if (amount > 0) depthTarget = amount / 3.0;
        }

        double next(double input) {
            wet = mix(wet, wetTarget, CONTROL);
            depth = mix(depth, depthTarget, CONTROL);
            double processed = effect.next(input, depth);
            return wet < .0001 ? input : mix(input, processed, wet);
        }
    }

    private interface Effect {
        double next(double input, double depth);
    }

    private static final class Wah implements Effect {
        private static final double ATTACK = smoothing(.006), RELEASE = smoothing(.065);
        private double envelope, left, right, a1, a2, b0;
        private int update;

        @Override
        public double next(double input, double depth) {
            envelope = follow(envelope, Math.abs(input), ATTACK, RELEASE);
            if ((update++ & 15) == 0) {
                double open = Math.clamp(envelope * (2.2 + 4.3 * depth), 0, 1);
                double center = 255 * Math.pow(8.8, open);
                // Constant peak gain band-pass biquad, with the envelope moving its center frequency.
                double angle = 2 * Math.PI * center / RATE;
                double alpha = Math.sin(angle) / (2 * (1.35 + depth));
                b0 = alpha / (1 + alpha);
                a1 = -2 * Math.cos(angle) / (1 + alpha);
                a2 = (1 - alpha) / (1 + alpha);
            }
            double band = b0 * input + left;
            left = right - a1 * band;
            right = -b0 * input - a2 * band;
            return mix(input, 1.8 * band, .36 + .27 * depth);
        }
    }

    private static final class Tremolo implements Effect {
        private double phase;

        @Override
        public double next(double input, double depth) {
            double value = .5 - .5 * Math.cos(2 * Math.PI * phase);
            phase = tickPhase(phase, 4.4);
            return input * (1 - (.18 + .72 * depth) * value);
        }
    }

    private static final class Chorus implements Effect {
        private final double[] buffer = new double[RATE * 37 / 1000];
        private double phase;
        private int cursor;

        @Override
        public double next(double input, double depth) {
            buffer[cursor] = input;
            double swing = .0011 + .0036 * depth;
            double tap1 = tap(.014 + swing * Math.sin(2 * Math.PI * phase));
            double tap2 = tap(.022 + .85 * swing * Math.sin(2 * Math.PI * (phase + .31)));
            if (++cursor == buffer.length) cursor = 0;
            phase = tickPhase(phase, .91);
            return mix(input, .61 * tap1 + .39 * tap2, .23 + .27 * depth);
        }

        private double tap(double seconds) {
            double index = cursor - seconds * RATE;
            if (index < 0) index += buffer.length;
            int lower = (int) index;
            int upper = lower == buffer.length - 1 ? 0 : lower + 1;
            return mix(buffer[lower], buffer[upper], index - lower);
        }
    }

    private static final class Compressor implements Effect {
        private static final double ATTACK = smoothing(.004), RELEASE = smoothing(.083);
        private double envelope, gain = 1, target = 1;
        private int update;

        @Override
        public double next(double input, double depth) {
            envelope = follow(envelope, Math.abs(input), ATTACK, RELEASE);
            if ((update++ & 15) == 0) {
                double excess = 20 * Math.log10(Math.max(1e-6, envelope)) + 14.5 + 7 * depth;
                // Soft knee over a 6 dB band centered at the threshold.
                double knee = Math.clamp(excess + 3, 0, 6);
                double reduction = (knee * knee / 12 + Math.max(0, excess - 3)) * (1 - 1 / (1.65 + 3.35 * depth));
                target = Math.pow(10, (1.8 + 3.8 * depth - reduction) / 20);
            }
            gain = follow(gain, target, RELEASE, ATTACK);
            return gain * input;
        }
    }

    private static double tickPhase(double phase, double hertz) {
        phase += hertz / RATE;
        return phase < 1 ? phase : phase - 1;
    }
}
