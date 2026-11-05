package org.haruyq.createmediasystems.stream;

import org.jetbrains.annotations.Nullable;

/**
 * A block entity that owns a track and how far in it is: a vanilla jukebox with a custom disc (see
 * JukeboxBlockEntityMixin), a disc reader or a Blu-ray player. Speakers and displays link to one and play it in sync.
 */
public interface MediaSource {
    /**
     * What it plays now, with the ticks since its track started (loading included: Streams counts what of that
     * has played, see Streams#playing), or null for nothing. A track starts with Streams#loading.
     */
    @Nullable
    Streams.Source nowPlaying();

    /** How loud its speakers play it, 0 to 1, on top of their own volume. */
    default float volume() {
        return 1;
    }

    /** A linked speaker plays it at {@code gameTime} (it does so every 10 ticks while it does). */
    default void playedBySpeaker(long gameTime) {
    }
}
