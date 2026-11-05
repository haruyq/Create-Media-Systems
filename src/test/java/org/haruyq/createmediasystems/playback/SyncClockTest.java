package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncClockTest {
    /** Where the track's top was for streams asked to start at {@code start} at {@code asked}. */
    private static long origin(long asked, double start) {
        return asked - Math.round(start * 1e9);
    }

    @Test
    void laterStreamsOfOnePlaybackSkipToTheFirst() throws Exception {
        long asked = System.nanoTime();
        assertEquals(0, SyncClock.start("song", 10, origin(asked, 10)).skipSeconds(), "the first stream plays at once");
        Thread.sleep(300);
        // Asked for the same start but ready 0.3 s later: drop 0.3 s to line up with the first.
        assertEquals(0.3, SyncClock.start("song", 10, origin(asked, 10)).skipSeconds(), 0.1);
        // Asked 0.05 s later (one server tick), also ready 0.3 s late.
        assertEquals(0.25, SyncClock.start("song", 10.05, origin(asked, 10.05)).skipSeconds(), 0.1);
    }

    /** A speaker that asked one tick ahead of its pair (the source ticked between them) waits for it. */
    @Test
    void aStreamAheadOfTheClockWaits() {
        long asked = System.nanoTime();
        SyncClock.start("pair", 10, origin(asked, 10));
        assertEquals(-0.05, SyncClock.start("pair", 10.05, origin(asked, 10.05)).skipSeconds(), 0.01);
    }

    /** A fresh disc's first loads take a while: streams asked for together still line up however late. */
    @Test
    void streamsAskedTogetherAreOnePlaybackHoweverLateTheyAreReady() throws Exception {
        long asked = System.nanoTime();
        SyncClock.start("fresh", 0, origin(asked, 0));
        Thread.sleep(2_100);
        assertEquals(2.1, SyncClock.start("fresh", 0, origin(asked, 0)).skipSeconds(), 0.2);
    }

    @Test
    void aFarOffStartIsAnotherPlayback() {
        long asked = System.nanoTime();
        SyncClock.start("other", 0, origin(asked, 0));
        assertEquals(0, SyncClock.start("other", 120, origin(asked, 120)).skipSeconds(),
                "a different playback must not skip");
    }

    /** Streams ready apart start on the device clock that far apart, all mapped from one moment. */
    @Test
    void streamsStartAsFarApartAsTheyWereReady() throws Exception {
        long asked = System.nanoTime();
        SyncClock.Start first = SyncClock.start("timed", 5, origin(asked, 5));
        Thread.sleep(50);
        SyncClock.Start second = SyncClock.start("timed", 5, origin(asked, 5));
        long firstAt = first.playAt(1_000_000_000L);
        // Asked later, at a device clock reading that has moved on in coarse steps: still mapped like the first.
        long secondAt = second.playAt(1_020_000_000L);
        assertEquals(second.readyAt() - first.readyAt(), secondAt - firstAt);
        assertEquals(0, second.playAt(0), "no device clock: start at once");
    }
}
