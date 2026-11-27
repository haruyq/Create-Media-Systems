package org.haruyq.createmediasystems.playback;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.api.media.client.LiveAudioFeeds;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.Streams;
import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Other mods' live sound on this client ({@link MediaKind#RAW}), by feed id (see api.media.client.LiveAudioFeeds). */
public final class LiveFeeds {
    private static final Map<ResourceLocation, LiveAudioFeeds.Opener> OPENERS = new ConcurrentHashMap<>();
    /** What one OpenAL buffer holds: 20 ms, refilled every few (see StreamPump), so a feed plays soon after it's made. */
    private static final int FRAMES = Pcm.RATE / 50;

    private LiveFeeds() {
    }

    public static void provide(ResourceLocation id, @Nullable LiveAudioFeeds.Opener opener) {
        if (opener == null) {
            OPENERS.remove(id);
        } else {
            OPENERS.put(id, opener);
        }
    }

    /** {@code track}'s feed for a speaker of {@code source} playing {@code channel}; null if there is none here. */
    @Nullable
    static CmsStream open(String track, @Nullable BlockPos source, Streams.Channel channel) {
        ResourceLocation id = ResourceLocation.tryParse(track.substring(MediaKind.RAW_SCHEME.length()));
        LiveAudioFeeds.Opener opener = id == null ? null : OPENERS.get(id);
        LiveAudioFeeds.Feed feed = null;
        try {
            feed = opener == null ? null : opener.open(source);
        } catch (RuntimeException failed) {
            LogUtils.getLogger().warn("The live audio feed {} failed to open", id, failed);
        }
        return feed == null ? null : new Stream(feed, channel);
    }

    /** One speaker's read of a feed: {@code channel} of it, in mono, silence wherever the feed has nothing. */
    private static final class Stream implements CmsStream {
        private final LiveAudioFeeds.Feed feed;
        private final Streams.Channel channel;
        private final ByteBuffer samples = ByteBuffer.allocateDirect(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);
        private final short[] stereo = new short[FRAMES * 2];
        private boolean failed;

        Stream(LiveAudioFeeds.Feed feed, Streams.Channel channel) {
            this.feed = feed;
            this.channel = channel;
        }

        @Override
        public AudioFormat getFormat() {
            return Pcm.FORMAT;
        }

        @Override
        public int bufferBytes(int vanillaBytes) {
            return FRAMES * 2;
        }

        @Override
        public boolean needsFastRefill() {
            return true;
        }

        @Override
        public ByteBuffer read(int size) {
            int filled = 0;
            if (!failed) {
                try {
                    filled = Math.clamp(feed.read(stereo, FRAMES), 0, FRAMES);
                } catch (RuntimeException error) {
                    // The sound thread is everyone's: a broken feed goes silent rather than take it down.
                    failed = true;
                    LogUtils.getLogger().warn("A live audio feed failed; it is silent from now on", error);
                }
            }
            samples.clear();
            for (int i = 0; i < FRAMES; i++) {
                samples.putShort(i < filled ? mono(stereo[2 * i], stereo[2 * i + 1]) : 0);
            }
            return samples.flip();
        }

        private short mono(short left, short right) {
            return switch (channel) {
                case LEFT -> left;
                case RIGHT -> right;
                case MIX -> (short) ((left + right) / 2);
            };
        }

        @Override
        public void close() {
            try {
                feed.close();
            } catch (RuntimeException error) {
                LogUtils.getLogger().warn("A live audio feed failed to close", error);
            }
        }
    }
}
