package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;

class StreamsLoadTest {
    @Test
    void aPhysicalDiscWaitsForDirectConnectionsAndBuffering() {
        String url = new org.haruyq.createmediasystems.disc.DiscUrl(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()).toString();
        assertEquals(Streams.MAX_LOAD_TICKS, Streams.barrierTicks(url));
        assertEquals(Streams.BARRIER_TICKS, Streams.barrierTicks("https://example.com/video.mp4"));
    }

    @Test
    void aTrackWaitsForItsListenersToLoadIt() {
        DiscReaderBlock.Entity source = reader();
        Streams.loading(source, null);
        assertEquals(0, Streams.played(source, 100), "still loading: nothing played yet");
        // Nobody had it loaded in time: it plays on from there.
        assertEquals(0, Streams.played(source, Streams.MAX_LOAD_TICKS));
        assertEquals(20, Streams.played(source, Streams.MAX_LOAD_TICKS + 20));
    }

    @Test
    void aSourceReloadedWithItsChunkKeepsItsLoadingTime() {
        DiscReaderBlock.Entity before = reader();
        Streams.loading(before, null);
        Streams.played(before, Streams.MAX_LOAD_TICKS);
        // Its chunk was unloaded and loaded again: a new block entity, loaded from what the old one saved.
        DiscReaderBlock.Entity after = reader();
        after.loadWithComponents(before.saveWithFullMetadata(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        assertEquals(20, Streams.played(after, Streams.MAX_LOAD_TICKS + 20));
    }

    @Test
    void aSourceNotStartedHereCountsEveryTick() {
        assertEquals(1234, Streams.played(reader(), 1234), "e.g. one already playing when the world loaded");
    }

    @Test
    void aTrackCountsAsLoadedOnlyOnceItPlays() {
        DiscReaderBlock.Entity source = reader();
        assertTrue(Streams.loaded(source), "never waited on");
        Streams.loading(source, null);
        Streams.played(source, 100);
        assertFalse(Streams.loaded(source), "lights keep still while it loads");
        Streams.played(source, Streams.MAX_LOAD_TICKS);
        assertTrue(Streams.loaded(source));
    }

    @Test
    void aListenersLengthEndsATrackTheApiCouldNotTell() {
        Streams.Source playing = new Streams.Source("https://example.com/listener-length", 0, null);
        Streams.learnDuration(playing, 0);
        Streams.learnDuration(playing, 100);
        assertFalse(Streams.endedAt(playing.url(), 1999));
        assertTrue(Streams.endedAt(playing.url(), 2000), "100 s at 20 ticks a second");
        Streams.learnDuration(playing, 300);
        assertTrue(Streams.endedAt(playing.url(), 2000), "the first length known stands");
    }

    private static DiscReaderBlock.Entity reader() {
        return new DiscReaderBlock.Entity(new BlockPos(7, 64, 7), AudioFeature.DISC_READER.get().defaultBlockState());
    }
}
