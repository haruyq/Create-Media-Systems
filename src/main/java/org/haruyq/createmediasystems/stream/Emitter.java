package org.haruyq.createmediasystems.stream;

import net.minecraft.server.level.ServerLevel;

/** A block entity linked to a source that plays its track to players near it, through Streams#relay. */
public interface Emitter {
    /** Relays its source's track now: every RELAY_TICKS, and at once when the source changed how it sounds. */
    void relay(ServerLevel level);

    /**
     * Whether it gives way to the source's other emitters: while one that doesn't plays the source, it plays silent. A
     * display's own sound gives way to speakers.
     */
    default boolean givesWay() {
        return false;
    }
}
