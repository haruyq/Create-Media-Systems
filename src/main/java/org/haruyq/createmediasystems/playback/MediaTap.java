package org.haruyq.createmediasystems.playback;

import java.util.function.IntUnaryOperator;

/**
 * Follows what a playback plays without changing it (the stage lights' analysis, a hi-fi's spectrum): offered its
 * sound as it is heard and its pictures as they are shown, while it is asked for (see MediaTaps).
 */
public interface MediaTap {
    /**
     * {@code count} samples of 16-bit little-endian {@code pcm}, {@code channels} interleaved (1 or 2), the first heard
     * at {@code pts} (the playback's clock, µs; see MediaTaps#now).
     */
    default void sound(byte[] pcm, int count, int channels, long pts) {
    }

    /** A picture {@code width} by {@code height}, {@code pixel} giving each pixel's RGB by index, shown at {@code at} (µs). */
    default void picture(int width, int height, IntUnaryOperator pixel, long at) {
    }

    /** What it was offered no longer goes on (a seek, a gap, another track): it starts over. */
    void clear();
}
