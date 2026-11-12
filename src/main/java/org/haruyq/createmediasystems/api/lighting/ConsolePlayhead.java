package org.haruyq.createmediasystems.api.lighting;

import org.jetbrains.annotations.ApiStatus;

/** Where a console's show is in its playing track, as last heard. */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface ConsolePlayhead {
    /** Seconds into the track when heard; NaN without a track. */
    double seconds();

    /** Its audio's motion phase when heard; NaN without one. */
    double phase();

    /** Seconds into the track at {@code now} (System.nanoTime); {@code fallback} without a track. */
    double seconds(long now, double fallback);
}
