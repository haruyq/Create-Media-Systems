package org.haruyq.createmediasystems.playback;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * Which playback of a track a stream plays. The streams of one playback on this client are heard as one: they share
 * a native player (NativeAudio), an emitter group (ClientStreams) and a start clock (SyncClock), all by
 * {@link #same}, so the three never disagree.
 *
 * @param source what plays it on the server (a source block); null for none (an announcement, a portable emitter)
 * @param load   which load of the source's track (see Streams.Load); 0 if unknown
 * @param disc   a moving disc's own identity (see StreamSpec#movingDisc); null for none
 * @param revision a portable device's transport revision: seeks and track changes start a fresh player
 * @param origin when the track's top was, as the stream was asked for (System.nanoTime)
 * @param midway whether it was asked to start part way in: a listener joining what already plays
 */
record PlaybackId(String track, @Nullable BlockPos source, long load, @Nullable UUID disc, int revision, long origin,
                  boolean midway) {
    /** Streams asked for tops further apart than this are of different playbacks (by time alone). */
    static final long SAME_NANOS = 2_000_000_000L;

    PlaybackId(String track, @Nullable BlockPos source, long load, @Nullable UUID disc, long origin, boolean midway) {
        this(track, source, load, disc, 0, origin, midway);
    }

    /** The playback a stream asked at {@code requestedAt} to play {@code startSeconds} into its track plays. */
    static PlaybackId of(String track, @Nullable BlockPos source, long load, @Nullable UUID disc,
                         double startSeconds, long requestedAt) {
        return of(track, source, load, disc, 0, startSeconds, requestedAt);
    }

    static PlaybackId of(String track, @Nullable BlockPos source, long load, @Nullable UUID disc, int revision,
                         double startSeconds, long requestedAt) {
        return new PlaybackId(track, source, load, disc, revision, requestedAt - Math.round(startSeconds * 1e9),
                startSeconds > 0);
    }

    /**
     * Whether {@code joining}, asked after this, plays this playback. A moving disc is its own within each revision. Of a
     * source, the server's load decides when both know it: the same load is one playback however long after the
     * first stream the next one came (while a track loads, every stream of it says 0 s, so by time alone a side whose
     * first speaker came 2 s later started over, and a stereo pair played apart), and another load is a restart.
     * Without it, one joining the same source mid-way does, as the server's position lags what is heard whenever it
     * runs below 20 TPS; else time decides, so a fresh start is new.
     */
    boolean same(PlaybackId joining) {
        if (!track.equals(joining.track) || !Objects.equals(disc, joining.disc)) {
            return false;
        }
        if (disc != null) {
            return revision == joining.revision;
        }
        if (!Objects.equals(source, joining.source)) {
            return false;
        }
        if (load != 0 && joining.load != 0) {
            return load == joining.load;
        }
        return joining.midway && source != null || Math.abs(origin - joining.origin) < SAME_NANOS;
    }

    /** The same playback, its track's top now taken to be at {@code origin} (where it really started). */
    PlaybackId at(long origin) {
        return new PlaybackId(track, source, load, disc, revision, origin, midway);
    }
}
