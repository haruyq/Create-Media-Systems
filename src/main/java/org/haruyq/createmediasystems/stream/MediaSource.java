package org.haruyq.createmediasystems.stream;

import org.jetbrains.annotations.Nullable;

/**
 * A block entity that owns a track and how far in it is, and how it sounds. Speakers and displays link to one and play
 * it in sync; it tells them through Streams#changed when how it sounds changes.
 */
public interface MediaSource {
    /**
     * What it plays now, as far in as its PlaybackClock is, or null for nothing. A track starts with Streams#loading,
     * and its clock stands until its listeners have it (see Streams#tick).
     */
    @Nullable
    Streams.Source nowPlaying();

    /** Its volume, 0 to 1, as it is set (and a remote's slider shows it). */
    default float volume() {
        return 1;
    }

    /**
     * How loud its speakers play it, 0 to 1, on top of their own volume: its {@link #volume} through the curve of its
     * knob, if that isn't straight.
     */
    default float gain() {
        return volume();
    }

    /** How it shapes its sound as it plays; read on each client from the block there, as its controls move. */
    default Tone tone() {
        return Tone.FLAT;
    }
}
