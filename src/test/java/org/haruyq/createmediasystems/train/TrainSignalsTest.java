package org.haruyq.createmediasystems.train;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TrainSignalsTest {
    private static final UUID STATION_A = UUID.randomUUID();
    private static final UUID STATION_A_EXIT = UUID.randomUUID();
    private static final UUID DEPOT = UUID.randomUUID();
    private static final UUID UNNAMED = UUID.randomUUID();
    private static final UUID SOMEONE_ELSES_OBSERVER = UUID.randomUUID();
    private static final Map<UUID, String> CHANNELS = Map.of(STATION_A, "a", STATION_A_EXIT, "a", DEPOT, "depot", UNNAMED, "");

    @Test
    void aChannelIsOnWhileAnyTrainIsOverAnyOfItsDetectors() {
        assertEquals(Set.of("a"), TrainSignals.activeChannels(CHANNELS, List.of(Set.of(STATION_A_EXIT))));
        assertEquals(Set.of("a", "depot"), TrainSignals.activeChannels(CHANNELS, List.of(Set.of(STATION_A), Set.of(DEPOT))));
        assertEquals(Set.of(), TrainSignals.activeChannels(CHANNELS, List.of(Set.of(UNNAMED, SOMEONE_ELSES_OBSERVER), Set.of())));
    }

    @Test
    void channelNamesAreTrimmedAndBounded() {
        assertEquals("station a", TrainSignals.normalize("  station a "));
        assertEquals("", TrainSignals.normalize("x".repeat(TrainSignals.MAX_CHANNEL_LENGTH + 1)));
        assertEquals("", TrainSignals.normalize("bad\nname"));
    }
}
