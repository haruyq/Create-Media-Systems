package org.haruyq.createmediasystems.lighting;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

final class AnalysisLease {
    /** A listener is ready while its last ready status is this recent (ms): three statuses missed, it is not. */
    static final long READY_MILLIS = 3 * AnalysisSession.STATUS_NANOS / 1_000_000;
    /** An owner that has published nothing for this long (ms) gives way to another ready listener. */
    static final long PUBLISH_MILLIS = 5_000;
    /** How long (ms) a session with no owner waits for its preferred listener before taking another. */
    static final long PREFERRED_MILLIS = 500;

    @Nullable UUID owner;
    int generation;
    long sequence = -1;
    long liveSequence = -1;
    long published;
    private long waiting;

    boolean elect(Map<UUID, Long> ready, @Nullable UUID preferred, long now) {
        ready.values().removeIf(at -> now - at > READY_MILLIS);
        if (owner != null && ready.containsKey(owner) && now - published < PUBLISH_MILLIS) {
            return false;
        }
        UUID previous = owner;
        if (previous != null) {
            ready.remove(previous);
        }
        if (ready.isEmpty()) {
            waiting = now;
            owner = null;
        } else if (preferred != null && ready.containsKey(preferred)) {
            owner = preferred;
        } else if (preferred == null || previous != null || now - waiting >= PREFERRED_MILLIS) {
            owner = ready.keySet().stream().min(UUID::compareTo).orElse(null);
        }
        if (Objects.equals(owner, previous)) {
            return false;
        }
        generation++;
        sequence = -1;
        liveSequence = -1;
        published = now;
        return true;
    }

    boolean accepts(UUID player, int epoch, long next, boolean prepared) {
        return player.equals(owner) && epoch == generation && next > (prepared ? sequence : liveSequence);
    }

    void accepted(long next, boolean prepared, long now) {
        if (prepared) {
            sequence = next;
        } else {
            liveSequence = next;
        }
        published = now;
    }
}
