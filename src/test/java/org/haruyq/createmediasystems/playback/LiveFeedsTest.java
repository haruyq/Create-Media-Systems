package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.haruyq.createmediasystems.api.media.RawAudio;
import org.haruyq.createmediasystems.api.media.client.LiveAudioFeeds;
import org.haruyq.createmediasystems.stream.Streams;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class LiveFeedsTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "feed");

    @Test
    void aSpeakerPlaysItsChannelOfWhatTheFeedMadeAndSilenceForTheRest() throws Exception {
        boolean[] closed = {false};
        LiveAudioFeeds.provide(ID, source -> new LiveAudioFeeds.Feed() {
            @Override
            public int read(short[] stereo, int frames) {
                stereo[0] = 1000;
                stereo[1] = 3000;
                return 1;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        });
        try {
            String link = RawAudio.feed(ID);
            assertEquals(1000, first(LiveFeeds.open(link, null, Streams.Channel.LEFT)));
            assertEquals(3000, first(LiveFeeds.open(link, null, Streams.Channel.RIGHT)));
            CmsStream mix = LiveFeeds.open(link, null, Streams.Channel.MIX);
            ByteBuffer read = mix.read(0).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2000, read.getShort(0));
            assertTrue(read.remaining() > 2 && read.getShort(2) == 0, "What it didn't make is silent");
            mix.close();
            assertTrue(closed[0]);
        } finally {
            LiveAudioFeeds.remove(ID);
        }
        assertNull(LiveFeeds.open(RawAudio.feed(ID), null, Streams.Channel.MIX), "Gone, it opens no more");
    }

    @Test
    void aFeedThatThrowsGoesSilent() throws Exception {
        LiveAudioFeeds.provide(ID, source -> (stereo, frames) -> {
            throw new IllegalStateException("broken");
        });
        try {
            CmsStream stream = LiveFeeds.open(RawAudio.feed(ID), null, Streams.Channel.MIX);
            assertEquals(0, stream.read(0).order(ByteOrder.LITTLE_ENDIAN).getShort(0));
            assertEquals(0, stream.read(0).order(ByteOrder.LITTLE_ENDIAN).getShort(0));
        } finally {
            LiveAudioFeeds.remove(ID);
        }
    }

    private static short first(CmsStream stream) throws Exception {
        return stream.read(0).order(ByteOrder.LITTLE_ENDIAN).getShort(0);
    }
}
