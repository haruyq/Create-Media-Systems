package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntUnaryOperator;

import org.haruyq.createmediasystems.playback.CachedDecoder;
import org.haruyq.createmediasystems.playback.MediaTap;
import org.haruyq.createmediasystems.playback.Pcm;
import org.haruyq.createmediasystems.playback.TimedFrames;

import org.jetbrains.annotations.Nullable;

/**
 * The lights' signal over the whole of a track kept on this computer, decoded ahead (see CachedDecoder): so the lights
 * follow it from its first frame, rather than as it plays. Worked out by the same analyser as a playing track's.
 */
final class CachedLightingAnalysis {
    private CachedLightingAnalysis() {
    }

    /**
     * The frames of the copy of {@code track} kept here for a disc playing at most {@code discHeight} lines tall, one
     * every {@link AnalysisFrame#INTERVAL} from its start, their motion phase run on from 0; null if no copy is kept,
     * or it is live or longer than {@link AnalysisFrame#MAX_DURATION}. Cancelling the result stops the decoding.
     */
    @Nullable
    static CompletableFuture<List<AnalysisFrame>> analyze(String track, int discHeight) {
        Timeline timeline = new Timeline(AnalysisFrame.MAX_DURATION, AnalysisFrame.INTERVAL);
        return CachedDecoder.decode(track, discHeight, AnalysisFrame.MAX_DURATION, timeline, timeline::finish);
    }

    /** What the decoder offers, kept by time: the sound heard every interval, and the colours of every picture. */
    static final class Timeline implements MediaTap {
        private static final long PICTURE_MICROS = 1_000_000 / CachedDecoder.FPS;
        private final long maxMillis;
        private final int interval;
        private final LightingAnalyzer analyzer = new LightingAnalyzer();
        private final TreeMap<Long, LightingAnalyzer.Sound> audio = new TreeMap<>();
        private final TreeMap<Long, LightingAnalyzer.Colors> video = new TreeMap<>();
        private long origin = Long.MAX_VALUE;
        private long end;
        private long nextAudio;

        Timeline(long maxMillis, int interval) {
            this.maxMillis = maxMillis;
            this.interval = interval;
        }

        @Override
        public synchronized void sound(byte[] pcm, int count, int channels, long pts) {
            origin = Math.min(origin, pts);
            int frame = channels * 2;
            for (int offset = 0; offset < count; offset += LightingAnalyzer.WINDOW) {
                int samples = Math.min(LightingAnalyzer.WINDOW, count - offset);
                long at = pts + (long) offset * 1_000_000 / Pcm.RATE;
                analyzer.sound(Arrays.copyOfRange(pcm, offset * frame, (offset + samples) * frame), samples, channels, at);
                LightingAnalyzer.Sound heard = analyzer.heard(at + (long) samples * 1_000_000 / Pcm.RATE);
                if (heard != null && heard.at() >= nextAudio) {
                    audio.put(heard.at(), heard);
                    nextAudio = heard.at() + (long) interval * 1000;
                }
            }
            end = Math.max(end, pts + (long) count * 1_000_000 / Pcm.RATE);
        }

        @Override
        public synchronized void picture(int width, int height, IntUnaryOperator pixel, long at) {
            origin = Math.min(origin, at);
            analyzer.picture(width, height, pixel, at);
            video.put(at, analyzer.shown(at));
            end = Math.max(end, at + PICTURE_MICROS);
        }

        @Override
        public void clear() {
        }

        /** One frame every interval ms from the start, their motion phase run on from 0. */
        synchronized List<AnalysisFrame> finish() {
            if (origin == Long.MAX_VALUE || audio.isEmpty() && video.isEmpty()) {
                throw new IllegalStateException("The cached media produced no lighting frames");
            }
            List<AnalysisFrame> frames = new ArrayList<>();
            long duration = Math.min(maxMillis, Math.max(1, (end - origin + 999) / 1000));
            double phase = 0;
            for (long millis = 0; millis < duration; millis += interval) {
                long at = origin + millis * 1000;
                Map.Entry<Long, LightingAnalyzer.Sound> sound = audio.floorEntry(at);
                if (sound == null && !audio.isEmpty() && audio.firstKey() - at <= 20_000) {
                    sound = audio.firstEntry();
                }
                Map.Entry<Long, LightingAnalyzer.Colors> shown = video.floorEntry(at);
                AnalysisFrame frame = LightingAnalyzer.frame(millis,
                        sound == null || at - sound.getKey() > TimedFrames.STALE_MICROS ? null : sound.getValue(),
                        shown == null || at - shown.getKey() >= LightingAnalyzer.SHOWN_MICROS ? null : shown.getValue());
                if (frame == null) {
                    frame = new AnalysisFrame(millis, 0, 0, 0, 0, 0, AnalysisFrame.DEFAULT_BPM, 0, 0, false, false);
                }
                if (!frames.isEmpty()) {
                    phase += interval / 1000.0 * frames.getLast().motionSpeed();
                }
                frames.add(frame.phase(phase));
            }
            return List.copyOf(frames);
        }
    }
}
