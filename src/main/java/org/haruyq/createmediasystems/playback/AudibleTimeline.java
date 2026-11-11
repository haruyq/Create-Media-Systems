package org.haruyq.createmediasystems.playback;

import java.util.ArrayDeque;
import java.util.Iterator;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;

/**
 * What an OpenAL source is heard playing now, rather than where its decoder has read ahead to: each buffer handed to
 * it, from when it is heard (see DeviceClock#presentationTime). Written on the sound thread, read from any.
 */
final class AudibleTimeline {
    /** Buffers kept: more than the engine ever keeps queued on a source. */
    private static final int KEPT = 32;
    /** How long past its end a buffer still counts as heard: the next one is that late at most, unless it ran dry. */
    private static final double SLACK_SECONDS = .025;

    /**
     * A buffer handed to the source: heard from {@code at} (System.nanoTime), {@code frames} frames of media from
     * {@code seconds} on (negative: silence, no place in the media), at {@code pitch}.
     */
    record Heard(long at, double seconds, int frames, float pitch, float rate) {
        /** Where in the media it is at {@code now}, within it. */
        double secondsAt(long now) {
            return seconds + Math.min((now - at) / 1e9 * pitch, frames / rate);
        }
    }

    private final ArrayDeque<Heard> queued = new ArrayDeque<>();
    private final float rate;
    private int source;
    private long startAt;
    private boolean closed;

    /** For audio of {@code rate} frames a second. */
    AudibleTimeline(float rate) {
        this.rate = rate;
    }

    /** It plays on the OpenAL {@code source} from scratch, starting at {@code startAt} (device clock; 0: at once). */
    synchronized void attach(int source, long startAt) {
        this.source = source;
        this.startAt = startAt;
        queued.clear();
    }

    /** On the sound thread: the source was handed {@code frames} frames of media from {@code seconds} on (see Heard). */
    synchronized void queue(double seconds, int frames) {
        if (source == 0 || frames <= 0 || closed) {
            return;
        }
        int count = DeviceClock.queuedBuffers(source);
        long ahead = 0;
        Iterator<Heard> latest = queued.descendingIterator();
        for (int i = 0; i < count && latest.hasNext(); i++) {
            ahead += latest.next().frames;
        }
        note(DeviceClock.presentationTime(source, startAt, ahead, rate), seconds, frames,
                AL10.alGetSourcef(source, AL10.AL_PITCH));
    }

    synchronized void note(long at, double seconds, int frames, float pitch) {
        queued.addLast(new Heard(at, seconds, frames, pitch, rate));
        while (queued.size() > KEPT) {
            queued.removeFirst();
        }
    }

    /** The buffer heard at {@code now}; null if none is (not yet, it ran dry, or it is closed). */
    @Nullable
    synchronized Heard at(long now) {
        Heard current = null;
        for (Heard buffer : queued) {
            if (buffer.at <= now) {
                current = buffer;
            }
        }
        boolean over = current != null && (now - current.at) / 1e9 * current.pitch > current.frames / rate + SLACK_SECONDS;
        return closed || over ? null : current;
    }

    /** Where in the media what is heard at {@code now} is (s); -1 if nothing, or silence, is. */
    double seconds(long now) {
        Heard heard = at(now);
        return heard == null || heard.seconds < 0 ? -1 : heard.secondsAt(now);
    }

    synchronized void close() {
        closed = true;
        queued.clear();
    }
}
