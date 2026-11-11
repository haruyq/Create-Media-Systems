package org.haruyq.createmediasystems;

/** Colours as 0xRRGGBB. */
public final class Rgb {
    private Rgb() {
    }

    public static int mix(int a, int b, float amount) {
        float t = Math.clamp(amount, 0, 1);
        return channel(a >> 16, b >> 16, t) << 16 | channel(a >> 8, b >> 8, t) << 8 | channel(a, b, t);
    }

    private static int channel(int a, int b, float t) {
        return Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
    }

    /** How bright {@code rgb} looks, 0 to 255 (Rec. 709 weights). */
    public static float luma(int rgb) {
        return ((rgb >> 16 & 255) * 54 + (rgb >> 8 & 255) * 183 + (rgb & 255) * 19) / 256f;
    }
}
