package org.haruyq.createmediasystems.playback;

import java.util.ArrayDeque;

import org.jetbrains.annotations.Nullable;

/**
 * Frames worked out from sound a moment before it is heard, each kept until it is heard (the playback's clock, µs):
 * a tap asking for now gets what is in the air now. Not synchronized: its tap guards it.
 */
public final class TimedFrames<T extends TimedFrames.Timed> {
    /** A frame heard longer ago than this (µs) is over: nothing is heard now (the playback stalled or stopped). */
    public static final long STALE_MICROS = 250_000;

    /** A frame of what is heard at {@link #at} (µs). */
    public interface Timed {
        long at();
    }

    private final ArrayDeque<T> frames = new ArrayDeque<>();
    private final int capacity;

    /** Keeping at most {@code capacity} frames ahead. */
    public TimedFrames(int capacity) {
        this.capacity = capacity;
    }

    public void add(T frame) {
        frames.addLast(frame);
        while (frames.size() > capacity) {
            frames.removeFirst();
        }
    }

    /** The frame heard at {@code at}, dropping those before it; null if none is yet, or the last is over. */
    @Nullable
    public T at(long at) {
        while (frames.size() > 1) {
            var iterator = frames.iterator();
            iterator.next();
            if (iterator.next().at() > at) {
                break;
            }
            frames.removeFirst();
        }
        T frame = frames.peekFirst();
        return frame == null || frame.at() > at || at - frame.at() > STALE_MICROS ? null : frame;
    }

    public void clear() {
        frames.clear();
    }
}
