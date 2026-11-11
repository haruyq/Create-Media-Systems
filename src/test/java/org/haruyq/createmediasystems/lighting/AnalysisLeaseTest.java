package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AnalysisLeaseTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** Long after any deadline a lease keeps. */
    private static final long LATER = 600_000;

    /** Whether {@code player} holds {@code lease}: its next upload of either kind is taken. */
    private static boolean holds(AnalysisLease lease, UUID player) {
        return lease.accepts(player, lease.generation, Long.MAX_VALUE, true);
    }

    @Test
    void electsReadyOwnerImmediatelyAndPrefersPreferred() {
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(FIRST, 0L);
        ready.put(SECOND, 0L);
        assertTrue(lease.elect(ready, SECOND, 0));
        assertTrue(holds(lease, SECOND));
        assertFalse(holds(lease, FIRST));
        assertTrue(lease.accepts(SECOND, lease.generation, 0, true));
        lease.accepted(0, true, 0);
        assertFalse(lease.accepts(SECOND, lease.generation, 0, true), "an upload is taken once");
    }

    @Test
    void doesNotStealActiveOwnerButRotatesAfterPublishDeadline() {
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(FIRST, 0L);
        assertTrue(lease.elect(ready, null, 0));
        ready.put(SECOND, 100L);
        assertFalse(lease.elect(ready, SECOND, 100), "the owner still publishes");
        ready.put(FIRST, LATER);
        ready.put(SECOND, LATER);
        assertTrue(lease.elect(ready, SECOND, LATER), "an owner ready but long silent is replaced");
        assertTrue(holds(lease, SECOND));
    }

    @Test
    void expiresReadyHeartbeatsAndRejectsOldGeneration() {
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(FIRST, 0L);
        assertTrue(lease.elect(ready, FIRST, 0));
        int oldGeneration = lease.generation;
        ready.put(SECOND, LATER);
        assertTrue(lease.elect(ready, SECOND, LATER));
        assertFalse(ready.containsKey(FIRST), "a heartbeat long gone no longer counts as ready");
        assertFalse(lease.accepts(FIRST, oldGeneration, 1, true));
    }

    @Test
    void cachedRetriesAndLiveFramesUseIndependentSequences() {
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(FIRST, 0L);
        assertTrue(lease.elect(ready, null, 0));
        int generation = lease.generation;
        lease.accepted(12, false, 0);
        assertTrue(lease.accepts(FIRST, generation, 1, true));
        lease.accepted(1, true, 0);
        assertFalse(lease.accepts(FIRST, generation, 1, true));
        assertFalse(lease.accepts(FIRST, generation, 12, false));
        assertTrue(lease.accepts(FIRST, generation, 13, false));

        ready.put(SECOND, LATER);
        assertTrue(lease.elect(ready, null, LATER));
        assertFalse(lease.accepts(FIRST, generation, 13, false));
        assertTrue(lease.accepts(SECOND, lease.generation, 0, false));
        assertTrue(lease.accepts(SECOND, lease.generation, 0, true));
    }
}
