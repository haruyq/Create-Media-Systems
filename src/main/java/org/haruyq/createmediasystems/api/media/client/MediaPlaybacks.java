package org.haruyq.createmediasystems.api.media.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntUnaryOperator;

import org.haruyq.createmediasystems.playback.CachedDecoder;
import org.haruyq.createmediasystems.playback.MediaTap;
import org.haruyq.createmediasystems.playback.MediaTaps;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.stream.Streams;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/** The media playing on this client, for a {@link MediaListener} to follow. */
@ApiStatus.Experimental
public final class MediaPlaybacks {
    private MediaPlaybacks() {
    }

    /**
     * Where a playback is: {@code millis} into its track is heard at {@code micros} on the clock its listener's
     * times are on.
     */
    public record Position(long millis, long micros) {
    }

    private record Tap(MediaListener listener) implements MediaTap {
        @Override
        public void sound(byte[] pcm, int count, int channels, long pts) {
            listener.sound(pcm, count, channels, pts);
        }

        @Override
        public void picture(int width, int height, IntUnaryOperator pixel, long at) {
            listener.picture(width, height, pixel, at);
        }

        @Override
        public void clear() {
            listener.restarted();
        }
    }

    /**
     * Offers {@code listener} the sound, and the pictures if it has any, of {@code track} as the source at
     * {@code source} plays it here, for the next two seconds: called again (each tick, say) it goes on. Where that
     * playback is now; null if the track doesn't play here (yet), and then nothing is offered.
     */
    @Nullable
    public static Position follow(BlockPos source, String track, MediaListener listener) {
        NativeAudio.Playback playback = NativeAudio.playback(source, track);
        MediaTaps taps = playback == null ? null : playback.taps();
        if (taps != null) {
            long millis = playback.time();
            return millis < 0 ? null : new Position(millis, tap(taps, listener));
        }
        // API streams, one a speaker: the mix if one plays, else whichever side does.
        MediaTaps.Relay heard = null;
        for (MediaTaps.Relay relay : MediaTaps.relays(source)) {
            if (track.equals(relay.track()) && (heard == null || relay.channel() == Streams.Channel.MIX)) {
                heard = relay;
            }
        }
        double seconds = heard == null ? -1 : heard.heardSeconds(System.nanoTime());
        return seconds < 0 ? null : new Position(Math.round(seconds * 1000), tap(heard.taps(), listener));
    }

    private static long tap(MediaTaps taps, MediaListener listener) {
        taps.keyed(listener, () -> new Tap(listener));
        return taps.now();
    }

    /**
     * Decodes the copy of {@code track} kept on this computer, as fast as it can, offering {@code listener} its
     * sound and small pictures, their times counted from wherever its first is. {@code videoHeight} is the tallest
     * picture its disc plays (see MediaSources.Track). Null if no copy is kept, or the track is live or longer than
     * {@code maxMillis}; cancelling the result stops the decoding.
     */
    @Nullable
    public static CompletableFuture<Void> decodeAhead(String track, int videoHeight, long maxMillis,
                                                      MediaListener listener) {
        return CachedDecoder.decode(track, videoHeight, maxMillis, new Tap(listener), () -> null);
    }
}
