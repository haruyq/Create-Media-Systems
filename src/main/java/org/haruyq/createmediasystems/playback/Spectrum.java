package org.haruyq.createmediasystems.playback;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

/**
 * What a playback sounds like from moment to moment, for a hi-fi stack's spectrum display and level meters: how loud
 * each of {@link #BANDS} octave bands is, and each channel. A tap of the native player (see MediaTaps), handed the
 * samples as they are decoded, a moment before they are heard.
 */
public final class Spectrum implements MediaTap {
    /** Octave bands, centred from 31.5 Hz up to 16 kHz. */
    public static final int BANDS = 10;
    private static final double LOWEST = 31.5;
    /** Samples transformed at a time, and how many new ones come between two frames. */
    private static final int WINDOW = 2048;
    private static final int HOP = WINDOW / 2;
    /** The quietest a band or channel shows (dB below full scale): 0 on the display. */
    private static final double FLOOR_DB = -60;
    private static final double[] HANN = new double[WINDOW];

    static {
        for (int i = 0; i < WINDOW; i++) {
            HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / WINDOW);
        }
    }

    /**
     * A moment's sound, heard at {@code at} (the playback's clock, µs): each band and each channel from 0
     * ({@link #FLOOR_DB} or less) to 1 (full scale).
     */
    public record Frame(long at, float[] bands, float left, float right) implements TimedFrames.Timed {
    }

    private final TimedFrames<Frame> frames = new TimedFrames<>(200);
    /** The last WINDOW samples (both channels mixed), the newest at {@code written - 1}. */
    private final double[] ring = new double[WINDOW];
    private long written;
    private int sinceFrame;
    private double leftEnergy;
    private double rightEnergy;

    @Override
    public synchronized void sound(byte[] pcm, int count, int channels, long pts) {
        int available = Math.min(count, pcm.length / (channels * 2));
        for (int i = 0; i < available; i++) {
            double left = Pcm.sample(pcm, i, channels, 0);
            double right = Pcm.sample(pcm, i, channels, 1);
            ring[(int) (written++ % WINDOW)] = (left + right) / 2;
            leftEnergy += left * left;
            rightEnergy += right * right;
            if (++sinceFrame == HOP) {
                frames.add(new Frame(pts + (i + 1) * 1_000_000L / Pcm.RATE, bands(),
                        level(Math.sqrt(leftEnergy / HOP)), level(Math.sqrt(rightEnergy / HOP))));
                sinceFrame = 0;
                leftEnergy = rightEnergy = 0;
            }
        }
    }

    /** How loud each band of the last WINDOW samples is. */
    private float[] bands() {
        double[] real = new double[WINDOW];
        double[] imaginary = new double[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            real[i] = ring[(int) ((written + i) % WINDOW)] * HANN[i];
        }
        transform(real, imaginary);
        float[] bands = new float[BANDS];
        for (int band = 0; band < BANDS; band++) {
            double centre = LOWEST * Math.pow(2, band);
            int from = Math.max(1, (int) Math.round(centre / Math.sqrt(2) * WINDOW / Pcm.RATE));
            int to = Math.min(WINDOW / 2 - 1, Math.max(from, (int) Math.round(centre * Math.sqrt(2) * WINDOW / Pcm.RATE)));
            double peak = 0;
            for (int bin = from; bin <= to; bin++) {
                peak = Math.max(peak, Math.hypot(real[bin], imaginary[bin]));
            }
            // A full-scale sine comes out of a Hann window WINDOW / 4 tall.
            bands[band] = level(peak / (WINDOW / 4.0));
        }
        return bands;
    }

    /** {@code amplitude} (1 is full scale) as a display shows it: 0 at {@link #FLOOR_DB}, 1 at full scale. */
    static float level(double amplitude) {
        return (float) Math.clamp(1 - 20 * Math.log10(Math.max(amplitude, 1e-9)) / FLOOR_DB, 0, 1);
    }

    /** The fast Fourier transform of {@code real} and {@code imaginary}, in place; their length is a power of two. */
    static void transform(double[] real, double[] imaginary) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double swap = real[i];
                real[i] = real[j];
                real[j] = swap;
                swap = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = swap;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            for (int start = 0; start < n; start += length) {
                for (int k = 0; k < length / 2; k++) {
                    double cos = Math.cos(angle * k);
                    double sin = Math.sin(angle * k);
                    int a = start + k;
                    int b = a + length / 2;
                    double re = real[b] * cos - imaginary[b] * sin;
                    double im = real[b] * sin + imaginary[b] * cos;
                    real[b] = real[a] - re;
                    imaginary[b] = imaginary[a] - im;
                    real[a] += re;
                    imaginary[a] += im;
                }
            }
        }
    }

    /** The frame heard at {@code at} (the playback's clock, µs); null if none is, or the last is over. */
    @Nullable
    synchronized Frame sample(long at) {
        return frames.at(at);
    }

    @Override
    public synchronized void clear() {
        frames.clear();
        Arrays.fill(ring, 0);
        sinceFrame = 0;
        leftEnergy = rightEnergy = 0;
    }
}
