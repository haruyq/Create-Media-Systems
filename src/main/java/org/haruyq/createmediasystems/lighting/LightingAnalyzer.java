package org.haruyq.createmediasystems.lighting;

import java.util.function.IntUnaryOperator;

import org.haruyq.createmediasystems.Rgb;
import org.haruyq.createmediasystems.playback.MediaTap;
import org.haruyq.createmediasystems.playback.Pcm;
import org.haruyq.createmediasystems.playback.TimedFrames;

import org.jetbrains.annotations.Nullable;

/**
 * What the stage lights follow of a playback: its loudness, bands and beat, and its picture's colours. A tap of the
 * native player or an API stream as it plays (see LightingMedia), and of a kept copy decoded ahead (see
 * CachedLightingAnalysis): both work it out here, the same way, so their frames can be spliced together.
 */
final class LightingAnalyzer implements MediaTap {
    /** Samples looked at together, a frame each (20 ms). */
    static final int WINDOW = Pcm.RATE / 50;
    /** Colours are worked out at most this often (µs): reading a picture costs. */
    static final long COLOR_CADENCE_MICROS = 50_000;
    /** A picture's colours take the lights over this long (µs), rather than at once. */
    static final long COLOR_RAMP_MICROS = 200_000;
    /** Colours shown longer ago than this (µs) are over: VLC shows far fewer pictures than it hands sound. */
    static final long SHOWN_MICROS = 1_000_000;

    /** A moment's sound, heard at {@code at} (µs): its level and bands (0 to 1), its beat's pulse (1 on it) and tempo. */
    record Sound(long at, float level, float bass, float mid, float high, float pulse, float bpm)
            implements TimedFrames.Timed {
        /** The two sides of one sound, heard as one. */
        static Sound both(Sound a, Sound b) {
            return new Sound(Math.max(a.at, b.at), energy(a.level, b.level), energy(a.bass, b.bass), energy(a.mid, b.mid),
                    energy(a.high, b.high), Math.max(a.pulse, b.pulse), a.pulse >= b.pulse ? a.bpm : b.bpm);
        }

        private static float energy(float a, float b) {
            return (float) Math.sqrt((a * a + b * b) / 2);
        }
    }

    /** A picture's colours: its left and right halves'. */
    record Colors(int left, int right) {
    }

    private final TimedFrames<Sound> frames = new TimedFrames<>(500);
    private final Filter[] low = {new Filter(250), new Filter(250)};
    private final Filter[] upper = {new Filter(3000), new Filter(3000)};
    private int samples;
    private long start;
    private double level;
    private double bass;
    private double mid;
    private double high;
    private double baseline;
    private float pulse;
    private float bpm = AnalysisFrame.DEFAULT_BPM;
    private long beat;
    private float smoothedLevel;
    private float smoothedBass;
    private float smoothedMid;
    private float smoothedHigh;
    @Nullable
    private Colors colors;
    private long colorsAt;

    /** The frame {@code millis} into its track of {@code sound} and {@code colors}; null if neither is there. */
    @Nullable
    static AnalysisFrame frame(long millis, @Nullable Sound sound, @Nullable Colors colors) {
        if (sound == null && colors == null) {
            return null;
        }
        return new AnalysisFrame(millis, sound == null ? 0 : sound.level, sound == null ? 0 : sound.bass,
                sound == null ? 0 : sound.mid, sound == null ? 0 : sound.high, sound == null ? 0 : sound.pulse,
                sound == null ? AnalysisFrame.DEFAULT_BPM
                        : Math.clamp(sound.bpm, AnalysisFrame.MIN_BPM, AnalysisFrame.MAX_BPM),
                colors == null ? 0 : colors.left, colors == null ? 0 : colors.right, sound != null, colors != null);
    }

    /** What is heard and shown at {@code at} (the playback's clock, µs), as the frame {@code millis} into its track. */
    @Nullable
    synchronized AnalysisFrame sample(long at, long millis) {
        return frame(millis, heard(at), shown(at));
    }

    /** The sound heard at {@code at} (µs); null if none is. */
    @Nullable
    synchronized Sound heard(long at) {
        return frames.at(at);
    }

    /** The colours shown at {@code at} (µs); null if none are. */
    @Nullable
    synchronized Colors shown(long at) {
        return colors != null && at - colorsAt >= 0 && at - colorsAt < SHOWN_MICROS ? colors : null;
    }

    @Override
    public synchronized void sound(byte[] pcm, int count, int channels, long pts) {
        int available = Math.min(count, pcm.length / (channels * 2));
        for (int i = 0; i < available; i++) {
            if (samples == 0) {
                start = pts + (long) i * 1_000_000 / Pcm.RATE;
            }
            for (int channel = 0; channel < 2; channel++) {
                double value = Pcm.sample(pcm, i, channels, channel);
                double lowValue = low[channel].apply(value);
                double upperValue = upper[channel].apply(value);
                level += value * value;
                bass += lowValue * lowValue;
                mid += (upperValue - lowValue) * (upperValue - lowValue);
                high += (value - upperValue) * (value - upperValue);
            }
            if (++samples == WINDOW) {
                finish(start + (long) WINDOW * 1_000_000 / Pcm.RATE);
            }
        }
    }

    private void finish(long at) {
        double energy = Math.sqrt(bass / (WINDOW * 2));
        pulse *= 0.85f;
        if (energy > 0.035 && energy > baseline * 1.6 && (beat == 0 || at - beat >= 250_000)) {
            if (beat != 0 && at - beat <= 1_200_000) {
                bpm += (60_000_000f / (at - beat) - bpm) * 0.25f;
            }
            beat = at;
            pulse = 1;
        }
        baseline += (energy - baseline) * 0.04;
        smoothedLevel = smooth(smoothedLevel, amplitude(level));
        smoothedBass = smooth(smoothedBass, amplitude(bass));
        smoothedMid = smooth(smoothedMid, amplitude(mid));
        smoothedHigh = smooth(smoothedHigh, amplitude(high));
        frames.add(new Sound(at, smoothedLevel, smoothedBass, smoothedMid, smoothedHigh, pulse, bpm));
        samples = 0;
        level = bass = mid = high = 0;
    }

    private static float amplitude(double energy) {
        return (float) Math.min(1, Math.sqrt(energy / (WINDOW * 2)) * 2.5);
    }

    private static float smooth(float previous, float next) {
        return previous + (next - previous) * (next > previous ? 0.6f : 0.12f);
    }

    /** Takes its colours, at most every COLOR_CADENCE, each turning the last over COLOR_RAMP. */
    @Override
    public synchronized void picture(int width, int height, IntUnaryOperator pixel, long at) {
        long since = at - colorsAt;
        if (colors != null && since >= 0 && since < COLOR_CADENCE_MICROS) {
            return;
        }
        int[] seen = colors(width, height, pixel);
        float amount = colors == null || since < 0 ? 1 : Math.min(1, since / (float) COLOR_RAMP_MICROS);
        colors = colors == null ? new Colors(seen[0], seen[1])
                : new Colors(Rgb.mix(colors.left, seen[0], amount), Rgb.mix(colors.right, seen[1], amount));
        colorsAt = at;
    }

    @Override
    public synchronized void clear() {
        frames.clear();
        samples = 0;
        start = beat = 0;
        level = bass = mid = high = baseline = 0;
        pulse = smoothedLevel = smoothedBass = smoothedMid = smoothedHigh = 0;
        bpm = AnalysisFrame.DEFAULT_BPM;
        for (Filter filter : low) {
            filter.clear();
        }
        for (Filter filter : upper) {
            filter.clear();
        }
        colors = null;
    }

    /** The left and right halves' colours of a picture: each the most vivid of its common colours, black bars aside. */
    static int[] colors(int width, int height, IntUnaryOperator pixel) {
        int[] result = new int[2];
        if (width <= 0 || height <= 0) {
            return result;
        }
        for (int side = 0; side < 2; side++) {
            int[] weights = new int[512];
            int[] reds = new int[512];
            int[] greens = new int[512];
            int[] blues = new int[512];
            for (int row = 0; row < 16; row++) {
                int y = (2 * row + 1) * height / 32;
                for (int column = 0; column < 12; column++) {
                    int x = (side * 24 + 2 * column + 1) * width / 48;
                    int color = pixel.applyAsInt(y * width + x);
                    int red = color >> 16 & 255;
                    int green = color >> 8 & 255;
                    int blue = color & 255;
                    int brightest = Math.max(red, Math.max(green, blue));
                    if (brightest < 16) {
                        continue;
                    }
                    int weight = brightest - Math.min(red, Math.min(green, blue)) + 32;
                    int bucket = (red >> 5) << 6 | (green >> 5) << 3 | blue >> 5;
                    weights[bucket] += weight;
                    reds[bucket] += red * weight;
                    greens[bucket] += green * weight;
                    blues[bucket] += blue * weight;
                }
            }
            int best = 0;
            for (int i = 1; i < weights.length; i++) {
                if (weights[i] > weights[best]) {
                    best = i;
                }
            }
            int weight = weights[best];
            result[side] = weight == 0 ? 0 : (reds[best] / weight) << 16
                    | (greens[best] / weight) << 8 | blues[best] / weight;
        }
        return result;
    }

    /** A second-order low-pass filter (RBJ biquad), one per channel and cutoff. */
    private static final class Filter {
        private final double a;
        private final double b;
        private final double c;
        private final double d;
        private final double e;
        private double x1;
        private double x2;
        private double y1;
        private double y2;

        Filter(double cutoff) {
            double omega = 2 * Math.PI * cutoff / Pcm.RATE;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / Math.sqrt(2);
            a = (1 - cosine) / 2 / (1 + alpha);
            b = (1 - cosine) / (1 + alpha);
            c = a;
            d = -2 * cosine / (1 + alpha);
            e = (1 - alpha) / (1 + alpha);
        }

        double apply(double value) {
            double output = a * value + b * x1 + c * x2 - d * y1 - e * y2;
            x2 = x1;
            x1 = value;
            y2 = y1;
            y1 = output;
            return output;
        }

        void clear() {
            x1 = x2 = y1 = y2 = 0;
        }
    }
}
