package org.haruyq.createmediasystems.api.media.client;

import java.util.function.IntUnaryOperator;

import org.haruyq.createmediasystems.playback.Pcm;
import org.jetbrains.annotations.ApiStatus;

/**
 * Follows what a playback plays without changing it (see {@link MediaPlaybacks}). Called from the threads that play
 * it, so it must return quickly: copy what it needs and work on it elsewhere.
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
public interface MediaListener {
    /** Samples a second of the sound it is offered. */
    int SAMPLE_RATE = Pcm.RATE;

    /**
     * {@code count} samples of 16-bit little-endian {@code pcm}, {@code channels} interleaved (1 or 2), the first
     * heard at {@code micros} on the playback's clock. {@code pcm} is the playback's own: not kept, nor changed.
     */
    default void sound(byte[] pcm, int count, int channels, long micros) {
    }

    /**
     * A picture {@code width} by {@code height}, shown at {@code micros} on the playback's clock. {@code pixel} gives
     * each pixel's 0xRRGGBB by index (row * width + column), and only until this returns.
     */
    default void picture(int width, int height, IntUnaryOperator pixel, long micros) {
    }

    /** What it was offered no longer goes on (a seek, a gap, another track): what follows starts over. */
    default void restarted() {
    }
}
