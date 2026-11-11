package org.haruyq.createmediasystems.stream;

import org.jetbrains.annotations.Nullable;

/** A block entity that owns a track and how far in it is. Speakers and displays link to one and play it in sync. */
public interface MediaSource {
    /**
     * What it plays now, as far in as its PlaybackClock is, or null for nothing. A track starts with Streams#loading,
     * and its clock stands until its listeners have it (see Streams#tick).
     */
    @Nullable
    Streams.Source nowPlaying();

    /** How loud its speakers play it, 0 to 1, on top of their own volume. */
    default float volume() {
        return 1;
    }
}
