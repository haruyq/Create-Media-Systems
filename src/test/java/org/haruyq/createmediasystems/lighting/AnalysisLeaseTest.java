package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AnalysisLeaseTest {
    @Test
    void electsReadyOwnerImmediatelyAndPrefersPreferred() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID preferred = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(first, 0L);
        ready.put(preferred, 0L);
        assertTrue(lease.elect(ready, preferred, 0));
        assertEquals(preferred, lease.owner);
        assertTrue(lease.accepts(preferred, lease.generation, 0, true));
        lease.accepted(0, true, 0);
        assertFalse(lease.accepts(preferred, lease.generation, 0, true), "an upload is taken once");
    }

    @Test
    void doesNotStealActiveOwnerButRotatesAfterPublishDeadline() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID challenger = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(owner, 0L);
        assertTrue(lease.elect(ready, null, 0));
        ready.put(challenger, 100L);
        assertFalse(lease.elect(ready, challenger, 1_000));
        ready.put(owner, 0L);
        ready.put(challenger, 5_001L);
        assertTrue(lease.elect(ready, challenger, 5_001));
        assertEquals(challenger, lease.owner);
    }

    @Test
    void expiresReadyHeartbeatsAndRejectsOldGeneration() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID next = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(owner, 0L);
        assertTrue(lease.elect(ready, owner, 0));
        int oldGeneration = lease.generation;
        ready.put(next, 5_001L);
        assertTrue(lease.elect(ready, next, 5_001));
        assertFalse(lease.accepts(owner, oldGeneration, 1, true));
    }

    @Test
    void cachedRetriesAndLiveFramesUseIndependentSequences() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID next = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AnalysisLease lease = new AnalysisLease();
        Map<UUID, Long> ready = new LinkedHashMap<>();
        ready.put(owner, 0L);
        assertTrue(lease.elect(ready, null, 0));
        int generation = lease.generation;
        lease.accepted(12, false, 0);
        assertTrue(lease.accepts(owner, generation, 1, true));
        lease.accepted(1, true, 0);
        assertFalse(lease.accepts(owner, generation, 1, true));
        assertFalse(lease.accepts(owner, generation, 12, false));
        assertTrue(lease.accepts(owner, generation, 13, false));

        ready.put(next, 3_001L);
        assertTrue(lease.elect(ready, null, 3_001));
        assertFalse(lease.accepts(owner, generation, 13, false));
        assertTrue(lease.accepts(next, lease.generation, 0, false));
        assertTrue(lease.accepts(next, lease.generation, 0, true));
    }
}
