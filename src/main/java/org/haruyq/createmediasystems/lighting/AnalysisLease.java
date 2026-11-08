package org.haruyq.createmediasystems.lighting;

import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

final class AnalysisLease {
    @Nullable UUID owner;
    int generation;
    long sequence = -1;
    long liveSequence = -1;
    long published;
    private long waiting;

    boolean elect(Map<UUID, Long> ready, @Nullable UUID preferred, long now) {
        ready.values().removeIf(at -> now - at > 3_000);
        if (owner != null && ready.containsKey(owner) && now - published < 5_000) {
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
        } else if (preferred == null || previous != null || now - waiting >= 500) {
            owner = ready.keySet().stream().min(UUID::compareTo).orElse(null);
        }
        if (java.util.Objects.equals(owner, previous)) {
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

    /** The owner's upload {@code next} (see accepts) was taken at {@code now}. */
    void accepted(long next, boolean prepared, long now) {
        if (prepared) {
            sequence = next;
        } else {
            liveSequence = next;
        }
        published = now;
    }
}
