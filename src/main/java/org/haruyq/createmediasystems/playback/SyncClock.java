package org.haruyq.createmediasystems.playback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lines up separately requested streams of one playback, e.g. a jukebox and its speakers, or a left and right
 * speaker. Each opens its own API stream and they become ready at slightly different moments, which a stereo
 * pair hears as an echo. The first to be ready starts the clock for its track; later ones skip ahead to it, or wait
 * for it (a speaker asked one tick later than its pair, as the source ticked between them). Each then starts on
 * OpenAL's clock at the moment it was lined up for, plus {@link DeviceClock#START_DELAY_NANOS} for all.
 */
final class SyncClock {
    // ponytail: never evicted, one clock per source and track played since start; drop a track's clock once no stream of it is open if that grows.
    private static final Map<String, Clock> CLOCKS = new ConcurrentHashMap<>();

    private static final class Clock {
        private final double startSeconds;
        private final long startedAt;
        /** The playback it times, as the stream that started it asked for it. */
        private final PlaybackId playback;
        /** The device clock at {@link #anchoredAt}, taken once so all streams of the playback map time alike. */
        private long deviceAt;
        private long anchoredAt;

        Clock(double startSeconds, long startedAt, PlaybackId playback) {
            this.startSeconds = startSeconds;
            this.startedAt = startedAt;
            this.playback = playback;
        }

        double positionAt(long now) {
            return startSeconds + (now - startedAt) / 1e9;
        }

        /** {@code wall} (System.nanoTime) on the device clock; on the sound thread. */
        synchronized long onDevice(long wall, long deviceNow) {
            if (anchoredAt == 0) {
                deviceAt = deviceNow;
                anchoredAt = System.nanoTime();
            }
            return deviceAt + (wall - anchoredAt);
        }
    }

    /** How a stream lines up: skip this much (negative: play that much silence first), then start at {@link #playAt}. */
    record Start(double skipSeconds, Clock clock, long readyAt) {
        /** The device clock time to start at, given it is {@code deviceNow} (0: OpenAL can't schedule, start at once). */
        long playAt(long deviceNow) {
            return deviceNow == 0 ? 0 : clock.onDevice(readyAt, deviceNow) + DeviceClock.START_DELAY_NANOS;
        }
    }

    private SyncClock() {
    }

    /**
     * How a stream of {@code playback}, ready now to start at {@code startSeconds}, lines up: with the clock of its
     * source's track if that times the same playback (see PlaybackId#same), so streams asked for together are one
     * playback however far apart they get ready (the first loads of a fresh disc take a while); else it starts the
     * clock over.
     */
    static Start start(PlaybackId playback, double startSeconds) {
        long now = System.nanoTime();
        String key = playback.source() + " " + playback.track();
        Clock clock = CLOCKS.get(key);
        if (clock != null && clock.playback.same(playback)) {
            return new Start(clock.positionAt(now) - startSeconds, clock, now);
        }
        clock = new Clock(startSeconds, now, playback);
        CLOCKS.put(key, clock);
        return new Start(0, clock, now);
    }
}
