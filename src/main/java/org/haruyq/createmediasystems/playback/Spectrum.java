package org.haruyq.createmediasystems.playback;

import java.util.ArrayDeque;
import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

/**
 * What a playback sounds like from moment to moment, for a hi-fi stack's spectrum display and level meters: how loud
 * each of {@link #BANDS} octave bands is, and each channel. VLC's audio thread hands it the samples as they are
 * decoded, a moment before they are heard; each frame keeps the time its samples are heard (VLC's clock), so a display
 * asking for now gets what is in the air now.
 */
public final class Spectrum {
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
     * A moment's sound, heard at {@code at} (VLC's clock, µs): each band and each channel from 0 ({@link #FLOOR_DB}
     * or less) to 1 (full scale).
     */
    public record Frame(long at, float[] bands, float left, float right) {
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    /** The last WINDOW samples (both channels mixed), the newest at {@code written - 1}. */
    private final double[] ring = new double[WINDOW];
    private long written;
    private int sinceFrame;
    private double leftEnergy;
    private double rightEnergy;
    private long end;

    /** {@code count} interleaved 16-bit stereo samples of {@code stereo}, the first heard at {@code pts}. */
    synchronized void offer(byte[] stereo, int count, long pts) {
        if (end != 0 && Math.abs(pts - end) > 50_000) {
            clear();
        }
        int available = Math.min(count, stereo.length / 4);
        for (int i = 0; i < available; i++) {
            double left = (short) (stereo[i * 4] & 0xff | stereo[i * 4 + 1] << 8) / 32768.0;
            double right = (short) (stereo[i * 4 + 2] & 0xff | stereo[i * 4 + 3] << 8) / 32768.0;
            ring[(int) (written++ % WINDOW)] = (left + right) / 2;
            leftEnergy += left * left;
            rightEnergy += right * right;
            if (++sinceFrame == HOP) {
                frames.addLast(new Frame(pts + (i + 1) * 1_000_000L / Pcm.RATE, bands(),
                        level(Math.sqrt(leftEnergy / HOP)), level(Math.sqrt(rightEnergy / HOP))));
                while (frames.size() > 200) {
                    frames.removeFirst();
                }
                sinceFrame = 0;
                leftEnergy = rightEnergy = 0;
            }
        }
        end = pts + available * 1_000_000L / Pcm.RATE;
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

    /** The frame heard at {@code at} (VLC's clock, µs); null if none is, or the last is long over. */
    @Nullable
    synchronized Frame sample(long at) {
        while (frames.size() > 1) {
            var iterator = frames.iterator();
            iterator.next();
            if (iterator.next().at > at) {
                break;
            }
            frames.removeFirst();
        }
        Frame frame = frames.peekFirst();
        return frame == null || frame.at > at || at - frame.at > 250_000 ? null : frame;
    }

    synchronized void clear() {
        frames.clear();
        Arrays.fill(ring, 0);
        sinceFrame = 0;
        leftEnergy = rightEnergy = 0;
        end = 0;
    }
}
