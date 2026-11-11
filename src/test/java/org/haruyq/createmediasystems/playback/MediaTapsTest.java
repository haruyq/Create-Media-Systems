package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import org.haruyq.createmediasystems.stream.Streams;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class MediaTapsTest {
    /** Keeps what it was offered. */
    static final class Recorder implements MediaTap {
        int samples;
        int clears;
        long lastPts;
        byte[] last = new byte[0];

        @Override
        public void sound(byte[] pcm, int count, int channels, long pts) {
            samples += count;
            last = pcm.clone();
            lastPts = pts;
        }

        @Override
        public void clear() {
            clears++;
        }
    }

    private static byte[] mono(int samples) {
        byte[] mono = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short value = (short) (i % 1000 + 1);
            mono[i * 2] = (byte) value;
            mono[i * 2 + 1] = (byte) (value >> 8);
        }
        return mono;
    }

    @Test
    void anApiStreamOffersOnlyWhatIsHeardWithoutConsumingTheSharedFeed() {
        byte[] mono = mono(Pcm.RATE);
        Feed.Reader feed = Feed.read(UUID.randomUUID().toString(), 0,
                offset -> new ByteArrayInputStream(mono), reason -> fail(reason));
        StreamSound.PcmStream stream = new StreamSound.PcmStream(feed, null, Streams.Channel.MIX, null, false);
        try {
            feed.prebuffer(mono.length, System.currentTimeMillis() + 5000);
            Recorder tap = stream.taps().get(Recorder.class, Recorder::new);
            long at = 10_000_000_000L;
            stream.heard.note(at, 0, mono.length / 2, 1);
            assertEquals((at - 1) / 1000, stream.heardUpTo(at - 1));
            assertEquals(0, tap.samples, "nothing is heard yet");
            stream.heardUpTo(at + 500_000_000);
            assertEquals(Pcm.RATE / 5, tap.samples, "the last fifth of a second heard");
            assertEquals(at / 1000 + 300_000, tap.lastPts);
            assertEquals(0, feed.position());
            assertArrayEquals(mono, feed.read(mono.length));
            stream.heard.note(at + 1_000_000_000, -1, Pcm.RATE / 4, 1);
            int clears = tap.clears;
            stream.heardUpTo(at + 1_100_000_000);
            assertTrue(tap.clears > clears, "silence starts over");
            assertArrayEquals(new byte[Pcm.BYTES_PER_SECOND / 10], tap.last, "and is offered as silence");
        } finally {
            stream.close();
        }
    }

    @Test
    void aGapInWhatATapIsOfferedStartsItOver() {
        MediaTaps taps = new MediaTaps(() -> 0);
        Recorder tap = taps.get(Recorder.class, Recorder::new);
        taps.sound(mono(10), 10, 1, 0);
        assertEquals(10, tap.samples);
        taps.sound(mono(10), 10, 1, 1_000_000);
        assertEquals(1, tap.clears, "a gap starts it over");
        assertSame(tap, taps.get(Recorder.class, Recorder::new));
    }

    @Test
    void aSourceHasNoRelayOnceItsLastStreamCloses() {
        Feed.Reader feed = Feed.read(UUID.randomUUID().toString(), 0,
                offset -> new ByteArrayInputStream(new byte[Pcm.BYTES_PER_SECOND]), reason -> fail(reason));
        BlockPos source = new BlockPos(12, 34, 56);
        StreamSound.PcmStream stream = new StreamSound.PcmStream(feed, source, Streams.Channel.MIX, "track", false);
        try {
            assertEquals(1, MediaTaps.relays(source).size());
            assertEquals("track", MediaTaps.relays(source).getFirst().track());
        } finally {
            stream.close();
        }
        assertTrue(MediaTaps.relays(source).isEmpty());
    }
}
