package org.haruyq.createmediasystems.stream;

import java.util.List;

/** A source that plays a playlist: its links, and which of them it is at. */
public interface PlaylistView {
    /** The links it plays in order; empty for none. */
    List<String> playlist();

    /** The entry playing (or paused, or stopped at). */
    int index();
}
