package org.haruyq.createmediasystems.stream;

/**
 * How a source's sound is shaped as it plays (see MediaSource#tone): {@code bands} as {@code low, high, gain} triples
 * (Hz, Hz, dB), each raised or lowered by its gain, and {@code width} of its stereo image (1 as recorded, more is
 * wider). Clients play it through playback's Equalizer.
 */
public record Tone(float[] bands, float width) {
    public static final Tone FLAT = new Tone(new float[0], 1);
    /** The most (dB) an equalizer raises or lowers a band. */
    public static final float MAX_GAIN = 12;
}
