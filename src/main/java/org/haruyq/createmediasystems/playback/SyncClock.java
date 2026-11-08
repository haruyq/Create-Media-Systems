package org.haruyq.createmediasystems.playback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * Lines up separately requested streams of one playback, e.g. a jukebox and its speakers, or a left and right
 * speaker. Each opens its own API stream and they become ready at slightly different moments, which a stereo
 * pair hears as an echo. The first to be ready starts the clock for its track; later ones skip ahead to it, or wait
 * for it (a speaker asked one tick later than its pair, as the source ticked between them). Each then starts on
 * OpenAL's clock at the moment it was lined up for, plus {@link DeviceClock#START_DELAY_NANOS} for all.
 */
final class SyncClock {
    /** Requested starts further than this from the clock are a different playback of the same track. */
    static final double SAME_PLAYBACK_SECONDS = 2;
    // ponytail: never evicted, one clock per source and track played since start; drop a track's clock once no stream of it is open if that grows.
    private static final Map<String, Clock> CLOCKS = new ConcurrentHashMap<>();

    private static final class Clock {
        private final double startSeconds;
        private final long startedAt;
        /** When the track's top was, by the request that started the clock (see start). */
        private final long origin;
        /** Which load of the track it times (see Streams.Load); 0 if unknown. */
        private final long load;
        /** The device clock at {@link #anchoredAt}, taken once so all streams of the playback map time alike. */
        private long deviceAt;
        private long anchoredAt;

        Clock(double startSeconds, long startedAt, long origin, long load) {
            this.startSeconds = startSeconds;
            this.startedAt = startedAt;
            this.origin = origin;
            this.load = load;
        }

        /** Whether a stream {@code skip} seconds off this clock, asked as {@code origin} and {@code load} say, is of its playback. */
        boolean times(double skip, long origin, long load) {
            if (load != 0 && this.load != 0) {
                return load == this.load;
            }
            return Math.abs(skip) < SAME_PLAYBACK_SECONDS || Math.abs(origin - this.origin) / 1e9 < SAME_PLAYBACK_SECONDS;
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
     * How a stream of {@code track} that asked to start at {@code startSeconds}, ready now, lines up. {@code origin}
     * is when the track's top was by its request (System.nanoTime when asked, less the start): streams asked for
     * together are one playback however far apart they get ready (the first loads of a fresh disc take a while).
     * {@code load} is which load of its source's track it is (see Streams.Load), 0 if unknown: known for both, it
     * alone decides, as while a track loads every stream of it asks for 0 s, so a speaker that came 2 s after its
     * pair (walking into its range, chunks loading in after a rejoin) started the track over, seconds behind it.
     */
    static Start start(@Nullable BlockPos source, String track, double startSeconds, long origin, long load) {
        long now = System.nanoTime();
        String key = source + " " + track;
        Clock clock = CLOCKS.get(key);
        if (clock != null) {
            double skip = clock.positionAt(now) - startSeconds;
            if (clock.times(skip, origin, load)) {
                return new Start(skip, clock, now);
            }
        }
        clock = new Clock(startSeconds, now, origin, load);
        CLOCKS.put(key, clock);
        return new Start(0, clock, now);
    }
}
