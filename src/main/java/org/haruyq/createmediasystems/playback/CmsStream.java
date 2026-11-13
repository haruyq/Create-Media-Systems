package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;

/**
 * Audio of CMS's own. The channel playing it starts it when it asks (see {@link #attach}, DeviceClock) and StreamPump
 * refills it between ticks; see playback/mixin/ChannelMixin, which recognizes it by this type.
 */
public interface CmsStream extends AudioStream {
    /**
     * Attached to the OpenAL {@code source}, at {@code deviceNow} (see DeviceClock#now); the device clock time to start
     * at, or 0 for at once.
     */
    default long attach(int source, long deviceNow) {
        return 0;
    }

    /** Bytes in each OpenAL buffer. Live instruments need a shorter queue than prerecorded media. */
    default int bufferBytes(int vanillaBytes) {
        return vanillaBytes;
    }

    /** Whether what it plays moves on while the game is paused, so a resumed channel starts over from it. */
    default boolean advancesWhilePaused() {
        return false;
    }

    /** A stream that changes the samples of {@code source} (a decorator): timed as that one is. */
    abstract class Wrapper implements CmsStream {
        protected final AudioStream source;

        protected Wrapper(AudioStream source) {
            this.source = source;
        }

        @Override
        public AudioFormat getFormat() {
            return source.getFormat();
        }

        @Override
        public long attach(int sourceId, long deviceNow) {
            return source instanceof CmsStream cms ? cms.attach(sourceId, deviceNow) : 0;
        }

        @Override
        public int bufferBytes(int vanillaBytes) {
            return source instanceof CmsStream cms ? cms.bufferBytes(vanillaBytes) : vanillaBytes;
        }

        @Override
        public boolean advancesWhilePaused() {
            return source instanceof CmsStream cms && cms.advancesWhilePaused();
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
