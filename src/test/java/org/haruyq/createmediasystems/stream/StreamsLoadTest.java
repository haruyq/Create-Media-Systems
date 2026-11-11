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
        String url = new DiscUrl(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()).toString();
        assertEquals(Streams.MAX_LOAD_TICKS, MediaKind.barrierTicks(url));
        assertEquals(Streams.BARRIER_TICKS, MediaKind.barrierTicks("https://example.com/video.mp4"));
    }

    @Test
    void aTrackWaitsForItsListenersToLoadIt() {
        DiscReaderBlock.Entity source = reader();
        PlaybackClock clock = started(source);
        tick(source, clock, 1, 100);
        assertEquals(0, clock.position(), "still loading: nothing played yet");
        // Nobody had it loaded in time: it plays on from there.
        tick(source, clock, 101, Streams.MAX_LOAD_TICKS);
        assertEquals(0, clock.position());
        tick(source, clock, Streams.MAX_LOAD_TICKS + 1, Streams.MAX_LOAD_TICKS + 20);
        assertEquals(20, clock.position());
    }

    @Test
    void aSourceReloadedWithItsChunkKeepsItsLoadingTime() {
        DiscReaderBlock.Entity before = reader();
        PlaybackClock clock = started(before);
        tick(before, clock, 1, Streams.MAX_LOAD_TICKS);
        // Its chunk was unloaded and loaded again: a new block entity, loaded from what the old one saved.
        DiscReaderBlock.Entity after = reader();
        after.loadWithComponents(before.saveWithFullMetadata(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        PlaybackClock again = new PlaybackClock();
        again.load(clock.position(), true);
        // It counts on from its first tick, as its listeners have the track.
        tick(after, again, Streams.MAX_LOAD_TICKS + 1, Streams.MAX_LOAD_TICKS + 21);
        assertEquals(20, again.position());
    }

    @Test
    void aSourceNotStartedHereCountsEveryTick() {
        DiscReaderBlock.Entity source = reader();
        PlaybackClock clock = new PlaybackClock();
        clock.load(1234, true);
        tick(source, clock, 50, 60);
        assertEquals(1244, clock.position(), "e.g. one already playing when the world loaded");
    }

    @Test
    void aTrackCountsAsLoadedOnlyOnceItPlays() {
        DiscReaderBlock.Entity source = reader();
        assertTrue(Streams.loaded(source), "never waited on");
        PlaybackClock clock = started(source);
        tick(source, clock, 1, 100);
        assertFalse(Streams.loaded(source), "lights keep still while it loads");
        tick(source, clock, 101, Streams.MAX_LOAD_TICKS);
        assertTrue(Streams.loaded(source));
    }

    @Test
    void aClockSkipsTheTicksItsBlockMissed() {
        DiscReaderBlock.Entity source = reader();
        PlaybackClock clock = new PlaybackClock();
        clock.load(0, true);
        tick(source, clock, 10, 20);
        tick(source, clock, 100, 110);
        // Ten ticks on from 10, then eleven from 100: loaded but not ticking from 21 to 99, it stood.
        assertEquals(10 + 11, clock.position());
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

    /** {@code source} starts its track from the top at game time 0, for its listeners to load. */
    private static PlaybackClock started(DiscReaderBlock.Entity source) {
        PlaybackClock clock = new PlaybackClock();
        clock.start(0);
        Streams.loading(source, null, clock, 0);
        return clock;
    }

    private static void tick(DiscReaderBlock.Entity source, PlaybackClock clock, long from, long to) {
        for (long now = from; now <= to; now++) {
            Streams.tick(source, clock, now);
        }
    }

    private static DiscReaderBlock.Entity reader() {
        return new DiscReaderBlock.Entity(new BlockPos(7, 64, 7), AudioFeature.DISC_READER.get().defaultBlockState());
    }
}
