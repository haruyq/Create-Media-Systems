package org.haruyq.createmediasystems.playback;

import java.util.Arrays;

/**
 * A source's tone as it plays here (see Tone): its stereo image widened, then one peaking filter per band, each
 * raising or lowering its band, from its low edge to its high edge, by its gain. Bands come as
 * {@code low, high, gain} triples (Hz, Hz, dB), set on the game's thread; VLC's audio thread filters with them.
 */
final class Equalizer {
    private volatile float[] bands = new float[0];
    /** How wide the stereo image is: 1 as recorded, 0 mono, more than 1 wider. */
    private volatile float width = 1;
    // VLC's audio thread only: the bands its filters were made for, each filter's coefficients and history per channel.
    private float[] built = bands;
    private double[][] filters = new double[0][];
    private double[][] history = new double[0][];

    void set(float[] bands) {
        set(bands, 1);
    }

    void set(float[] bands, float width) {
        if (!Arrays.equals(bands, this.bands)) {
            this.bands = bands;
        }
        this.width = width;
    }

    /** Filters VLC's interleaved 16-bit stereo {@code stereo} in place. */
    void process(byte[] stereo) {
        float[] bands = this.bands;
        if (bands != built) {
            if (bands.length != built.length) {
                history = new double[bands.length / 3][8];
            }
            filters = new double[bands.length / 3][];
            for (int i = 0; i < filters.length; i++) {
                filters[i] = peaking(bands[i * 3], bands[i * 3 + 1], bands[i * 3 + 2]);
            }
            built = bands;
        }
        float width = this.width;
        if (filters.length == 0 && width == 1) {
            return;
        }
        for (int i = 0; i + 3 < stereo.length; i += 4) {
            double left = (short) (stereo[i] & 0xff | stereo[i + 1] << 8);
            double right = (short) (stereo[i + 2] & 0xff | stereo[i + 3] << 8);
            if (width != 1) {
                double middle = (left + right) / 2;
                double side = (left - right) / 2 * width;
                left = middle + side;
                right = middle - side;
            }
            put(stereo, i, filter(left, 0));
            put(stereo, i + 2, filter(right, 4));
        }
    }

    /** {@code x} through every filter, with the history of the channel at {@code channel} (0 left, 4 right). */
    private double filter(double x, int channel) {
        for (int f = 0; f < filters.length; f++) {
            double[] c = filters[f];
            double[] h = history[f];
            double y = c[0] * x + c[1] * h[channel] + c[2] * h[channel + 1] - c[3] * h[channel + 2] - c[4] * h[channel + 3];
            h[channel + 1] = h[channel];
            h[channel] = x;
            h[channel + 3] = h[channel + 2];
            h[channel + 2] = y;
            x = y;
        }
        return x;
    }

    private static void put(byte[] stereo, int at, double value) {
        int sample = Math.clamp(Math.round(value), Short.MIN_VALUE, Short.MAX_VALUE);
        stereo[at] = (byte) sample;
        stereo[at + 1] = (byte) (sample >> 8);
    }

    /**
     * The peaking filter (RBJ's audio EQ cookbook) for the band {@code low}..{@code high} Hz at {@code gain} dB, as
     * {@code b0, b1, b2, a1, a2} over a0.
     */
    static double[] peaking(double low, double high, double gain) {
        double w = 2 * Math.PI * Math.sqrt(low * high) / Pcm.RATE;
        double alpha = Math.sin(w) * Math.sinh(Math.log(high / low) / 2 * w / Math.sin(w));
        double a = Math.pow(10, gain / 40);
        double a0 = 1 + alpha / a;
        return new double[]{(1 + alpha * a) / a0, -2 * Math.cos(w) / a0, (1 - alpha * a) / a0,
                -2 * Math.cos(w) / a0, (1 - alpha / a) / a0};
    }
}
