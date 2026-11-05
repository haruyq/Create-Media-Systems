package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class FeedTest {
    /** Speakers of one source: one download, the same audio for each, from where each asked. */
    @Test
    void streamsOfOneAudioShareADownload() {
        byte[] audio = new byte[Pcm.BYTES_PER_SECOND * 3];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i / Pcm.BYTES_PER_SECOND + 1);
        }
        AtomicInteger requests = new AtomicInteger();
        Feed.Resume open = offset -> {
            requests.incrementAndGet();
            return new ByteArrayInputStream(audio);
        };
        Feed.Reader first = Feed.read("song", 0, open, reason -> { });
        Feed.Reader second = Feed.read("song", 1, open, reason -> { });
        first.prebuffer(audio.length, System.currentTimeMillis() + 5_000);
        assertEquals(1, requests.get(), "the second stream downloaded it again");
        assertEquals(1, first.read(10)[0]);
        assertEquals(2, second.read(10)[0], "the second stream did not start a second in");

        // Another song, or this one from a part no longer held: a download of its own.
        Feed.Reader other = Feed.read("other", 0, open, reason -> { });
        Feed.Reader later = Feed.read("song", 60, open, reason -> { });
        other.prebuffer(1, System.currentTimeMillis() + 5_000);
        later.prebuffer(1, System.currentTimeMillis() + 5_000);
        assertEquals(3, requests.get());
        for (Feed.Reader reader : new Feed.Reader[] {first, second, other, later}) {
            reader.close();
        }
    }

    /** Once every stream of it has stopped, the next starts the audio afresh. */
    @Test
    void theLastStreamOutStopsTheDownload() {
        AtomicInteger requests = new AtomicInteger();
        Feed.Resume open = offset -> {
            requests.incrementAndGet();
            return new ByteArrayInputStream(new byte[1000]);
        };
        Feed.read("song", 0, open, reason -> { }).close();
        Feed.Reader again = Feed.read("song", 0, open, reason -> { });
        again.prebuffer(1000, System.currentTimeMillis() + 5_000);
        assertEquals(2, requests.get());
        assertArrayEquals(new byte[1000], again.read(2000));
        assertTrue(again.done());
        again.close();
    }
}
