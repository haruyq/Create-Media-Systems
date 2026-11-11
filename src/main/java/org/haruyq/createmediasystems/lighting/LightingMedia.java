package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.playback.MediaTaps;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * What the stage lights follow of the media playing here, as a tap of the native player or the API streams that play
 * it (see LightingAnalyzer); and where in it that playback is (see clock).
 */
final class LightingMedia {
    private LightingMedia() {
    }

    /**
     * Where the playback of {@code track} from the source at {@code source} is here, in ms (-1 if it doesn't play
     * yet); whether a native player plays it, and if so whether it is live with its media still coming in.
     */
    record Clock(long millis, boolean nativePlayer, boolean liveAndFlowing) {
    }

    /** The signal of {@code track} as the source at {@code source} plays it here now, as the frame {@code millis} in. */
    @Nullable
    static AnalysisFrame sample(BlockPos source, String track, long millis) {
        NativeAudio.Playback playback = NativeAudio.playback(source, track);
        MediaTaps taps = playback == null ? null : playback.taps();
        LightingAnalyzer.Sound sound = null;
        LightingAnalyzer.Colors colors = null;
        if (taps != null) {
            LightingAnalyzer analyzer = analyzer(taps);
            long now = taps.now();
            sound = analyzer.heard(now);
            colors = analyzer.shown(now);
        }
        return LightingAnalyzer.frame(millis, sound == null ? relayed(source, track) : sound, colors);
    }

    static Clock clock(BlockPos source, String track, long now) {
        NativeAudio.Playback playback = NativeAudio.playback(source, track);
        if (playback != null && playback.plays()) {
            return new Clock(playback.time(), true, playback.liveAndFlowing(now));
        }
        for (MediaTaps.Relay relay : MediaTaps.relays(source)) {
            double seconds = track.equals(relay.track()) ? relay.heardSeconds(now) : -1;
            if (seconds >= 0) {
                return new Clock(Math.round(seconds * 1000), false, false);
            }
        }
        return new Clock(-1, false, false);
    }

    private static LightingAnalyzer analyzer(MediaTaps taps) {
        return taps.get(LightingAnalyzer.class, LightingAnalyzer::new);
    }

    /** The sound of {@code track} as the API streams of {@code source} play it: its mix, else its two sides as one. */
    @Nullable
    static LightingAnalyzer.Sound relayed(BlockPos source, @Nullable String track) {
        LightingAnalyzer.Sound left = null;
        LightingAnalyzer.Sound right = null;
        for (MediaTaps.Relay relay : MediaTaps.relays(source)) {
            if (track != null && !track.equals(relay.track())
                    || relay.channel() == Streams.Channel.LEFT && left != null
                    || relay.channel() == Streams.Channel.RIGHT && right != null) {
                continue;
            }
            LightingAnalyzer.Sound heard = analyzer(relay.taps()).heard(relay.taps().now());
            switch (relay.channel()) {
                case MIX -> {
                    if (heard != null) {
                        return heard;
                    }
                }
                case LEFT -> left = heard;
                case RIGHT -> right = heard;
            }
        }
        return left == null || right == null ? left == null ? right : left : LightingAnalyzer.Sound.both(left, right);
    }
}
