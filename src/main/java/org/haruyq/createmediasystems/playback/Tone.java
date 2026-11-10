package org.haruyq.createmediasystems.playback;

/**
 * How a source's sound is shaped as it plays here: {@code bands} as {@code low, high, gain} triples (Hz, Hz, dB), each
 * raised or lowered by its gain, and {@code width} of its stereo image (1 as recorded, more is wider). See Equalizer.
 */
public record Tone(float[] bands, float width) {
    public static final Tone FLAT = new Tone(new float[0], 1);
}
