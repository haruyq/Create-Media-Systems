package org.haruyq.createmediasystems.items.instrument.client;

import org.haruyq.createmediasystems.items.instrument.GuitarPatch;
import org.haruyq.createmediasystems.items.instrument.MetalZoneBlock.Knob;
import org.haruyq.createmediasystems.items.instrument.MetalZoneSettings;
import org.haruyq.createmediasystems.playback.Pcm;

/** Two stages of drive, followed by active low, sweepable middle and high EQ. */
final class MetalZoneDrive implements PedalRack.Effect {
    private static final int OVERSAMPLE = 4;
    private static final int DRIVE_RATE = Pcm.RATE * OVERSAMPLE;
    private static final int CONTROL_INTERVAL = 32;
    private static final double CONTROL_SLEW = -Math.expm1(-CONTROL_INTERVAL / (.020 * Pcm.RATE));
    private static final Knob[] KNOBS = Knob.values();
    private final double[] positions = new double[KNOBS.length];
    private final Filter inputCut = Filter.pass(84, DRIVE_RATE, true);
    private final Filter presence = new Filter();
    private final Filter antialias = Filter.pass(8_400, DRIVE_RATE, false);
    private final Filter antialiasSecond = Filter.pass(8_400, DRIVE_RATE, false);
    private final Filter dcBlock = Filter.pass(29, Pcm.RATE, true);
    private final Filter voice = Filter.pass(6_100, Pcm.RATE, false);
    private final Filter low = new Filter();
    private final Filter mid = new Filter();
    private final Filter high = new Filter();
    private MetalZoneSettings target;
    private double previousInput, firstGain, secondGain, level;
    private boolean moving;
    private int frame;

    MetalZoneDrive(MetalZoneSettings settings) {
        target = settings;
        for (Knob knob : KNOBS) positions[knob.ordinal()] = settings.value(knob);
        presence.peak(785, 1.05, 6.5, DRIVE_RATE);
        tune();
    }

    @Override
    public void configure(GuitarPatch.Setting setting) {
        if (!target.equals(setting.metalZone())) {
            target = setting.metalZone();
            moving = true;
        }
    }

    @Override
    public double next(double input, double depth) {
        if (moving && (frame++ & (CONTROL_INTERVAL - 1)) == 0) followControls();
        // Interpolate into the nonlinear stages and filter before decimating to the output rate.
        double step = (input - previousInput) / OVERSAMPLE;
        double sample = previousInput;
        double driven = 0;
        for (int i = 0; i < OVERSAMPLE; i++) {
            sample += step;
            double shaped = presence.next(inputCut.next(sample)) * firstGain;
            double overdrive = shaped / (1 + .80 * Math.abs(shaped));
            double diode = Math.clamp(overdrive * secondGain, -1, 1);
            driven = antialiasSecond.next(antialias.next(diode * (1.5 - .5 * diode * diode)));
        }
        previousInput = input;
        double signal = level * high.next(mid.next(low.next(voice.next(dcBlock.next(driven)))));
        double excess = Math.abs(signal) - .82;
        return excess <= 0 ? signal : Math.copySign(.82 + excess / (1 + excess / .17), signal);
    }

    private void followControls() {
        moving = false;
        for (Knob knob : KNOBS) {
            int i = knob.ordinal();
            int requested = target.value(knob);
            double difference = requested - positions[i];
            if (Math.abs(difference) < .010) {
                positions[i] = requested;
            } else {
                positions[i] += difference * CONTROL_SLEW;
                moving = true;
            }
        }
        tune();
    }

    private double position(Knob knob) {
        return positions[knob.ordinal()];
    }

    private void tune() {
        double drive = position(Knob.DIST) / 100;
        firstGain = 1.25 + 3.9 * Math.pow(drive, 1.35);
        secondGain = 1.05 * Math.pow(22, drive);
        level = .96 * Math.pow(position(Knob.LEVEL) / 100, 1.8);
        low.shelf(118, MetalZoneSettings.eqDb(position(Knob.LOW)), false);
        mid.peak(MetalZoneSettings.midHertz(position(Knob.MID_FREQ)), 1.20,
                MetalZoneSettings.eqDb(position(Knob.MIDDLE)), Pcm.RATE);
        high.shelf(3_750, MetalZoneSettings.eqDb(position(Knob.HIGH)), true);
    }

    /** Biquads using the RBJ cookbook coefficients and transposed direct form II. */
    private static final class Filter {
        private double b0, b1, b2, a1, a2, z1, z2;

        static Filter pass(double frequency, int rate, boolean highPass) {
            double angle = 2 * Math.PI * frequency / rate;
            double cosine = Math.cos(angle);
            double alpha = Math.sin(angle) / Math.sqrt(2);
            double edge = .5 * (highPass ? 1 + cosine : 1 - cosine);
            var filter = new Filter();
            filter.set(edge, highPass ? -2 * edge : 2 * edge, edge, 1 + alpha, -2 * cosine, 1 - alpha);
            return filter;
        }

        void peak(double frequency, double q, double db, int rate) {
            double a = Math.pow(10, db / 40);
            double angle = 2 * Math.PI * frequency / rate;
            double middle = -2 * Math.cos(angle);
            double alpha = Math.sin(angle) / (2 * q);
            set(1 + alpha * a, middle, 1 - alpha * a, 1 + alpha / a, middle, 1 - alpha / a);
        }

        void shelf(double frequency, double db, boolean upper) {
            double a = Math.pow(10, db / 40);
            double angle = 2 * Math.PI * frequency / Pcm.RATE;
            double sign = upper ? 1 : -1;
            double cosine = sign * Math.cos(angle);
            double slope = Math.sqrt(2 * a) * Math.sin(angle);
            double sum = a + 1;
            double diff = a - 1;
            set(a * (sum + diff * cosine + slope), -2 * sign * a * (diff + sum * cosine),
                    a * (sum + diff * cosine - slope), sum - diff * cosine + slope,
                    2 * sign * (diff - sum * cosine), sum - diff * cosine - slope);
        }

        private void set(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0;
            this.b1 = b1 / a0;
            this.b2 = b2 / a0;
            this.a1 = a1 / a0;
            this.a2 = a2 / a0;
        }

        double next(double input) {
            double output = input * b0 + z1;
            z1 = input * b1 - a1 * output + z2;
            z2 = input * b2 - a2 * output;
            return output;
        }
    }
}
