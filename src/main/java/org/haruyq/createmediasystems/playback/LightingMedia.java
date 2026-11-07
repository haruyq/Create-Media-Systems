package org.haruyq.createmediasystems.playback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

public final class LightingMedia {
    private static final Map<BlockPos, LinkedHashSet<StreamSound.PcmStream>> RELAYS = new HashMap<>();

    private LightingMedia() {
    }

    public record Sample(float level, float bass, float mid, float high, float pulse, float bpm,
                         int leftColor, int rightColor, boolean audio, boolean video) {
    }

    @Nullable
    public static Sample sample(@Nullable BlockPos audioSource, @Nullable BlockPos videoSource) {
        return sample(audioSource, videoSource, null);
    }

    @Nullable
    public static Sample sample(@Nullable BlockPos audioSource, @Nullable BlockPos videoSource, @Nullable String track) {
        VlcPlayer audioPlayer = audioSource == null ? null : NativeAudio.lighting(audioSource, track);
        VlcPlayer videoPlayer = videoSource == null ? null : NativeAudio.lighting(videoSource, track);
        AudioFrame audio = audioPlayer == null ? null : audioPlayer.audio.lighting();
        if (audio == null && audioSource != null) {
            audio = relay(audioSource, track);
        }
        Colors video = videoPlayer == null || videoPlayer.frame == null ? null : videoPlayer.frame.lighting();
        return audio == null && video == null ? null : new Sample(
                audio == null ? 0 : audio.level, audio == null ? 0 : audio.bass,
                audio == null ? 0 : audio.mid, audio == null ? 0 : audio.high,
                audio == null ? 0 : audio.pulse, audio == null ? 120 : audio.bpm,
                video == null ? 0 : video.left, video == null ? 0 : video.right, audio != null, video != null);
    }

    record AudioFrame(long at, float level, float bass, float mid, float high, float pulse, float bpm) {
    }

    static void openRelay(BlockPos source, StreamSound.PcmStream stream) {
        synchronized (RELAYS) {
            RELAYS.computeIfAbsent(source.immutable(), key -> new LinkedHashSet<>()).add(stream);
        }
    }

    static void closeRelay(BlockPos source, StreamSound.PcmStream stream) {
        synchronized (RELAYS) {
            LinkedHashSet<StreamSound.PcmStream> streams = RELAYS.get(source);
            if (streams != null && streams.remove(stream) && streams.isEmpty()) {
                RELAYS.remove(source);
            }
        }
    }

    @Nullable
    static AudioFrame relay(BlockPos source) {
        return relay(source, null);
    }

    @Nullable
    static AudioFrame relay(BlockPos source, @Nullable String track) {
        ArrayList<StreamSound.PcmStream> streams;
        synchronized (RELAYS) {
            LinkedHashSet<StreamSound.PcmStream> playing = RELAYS.get(source);
            if (playing == null) {
                return null;
            }
            streams = new ArrayList<>(playing);
        }
        StreamSound.PcmStream left = null;
        StreamSound.PcmStream right = null;
        for (StreamSound.PcmStream stream : streams) {
            if (track != null && !track.equals(stream.track)) {
                continue;
            }
            if (stream.channel() == Streams.Channel.MIX) {
                AudioFrame mix = stream.lighting();
                if (mix != null) {
                    return mix;
                }
            } else if (stream.channel() == Streams.Channel.LEFT && left == null) {
                left = stream;
            } else if (stream.channel() == Streams.Channel.RIGHT && right == null) {
                right = stream;
            }
        }
        AudioFrame a = left == null ? null : left.lighting();
        AudioFrame b = right == null ? null : right.lighting();
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        return new AudioFrame(Math.max(a.at, b.at), energy(a.level, b.level), energy(a.bass, b.bass),
                energy(a.mid, b.mid), energy(a.high, b.high), Math.max(a.pulse, b.pulse),
                a.pulse >= b.pulse ? a.bpm : b.bpm);
    }

    private static float energy(float a, float b) {
        return (float) Math.sqrt((a * a + b * b) / 2);
    }

    static final class Audio {
        private static final int WINDOW = Pcm.RATE / 50;
        private final ArrayDeque<AudioFrame> frames = new ArrayDeque<>();
        private final Filter[] low = {new Filter(250), new Filter(250)};
        private final Filter[] upper = {new Filter(3000), new Filter(3000)};
        private int samples;
        private long start;
        private long end;
        private double level;
        private double bass;
        private double mid;
        private double high;
        private double baseline;
        private float pulse;
        private float bpm = 120;
        private long beat;
        private float smoothedLevel;
        private float smoothedBass;
        private float smoothedMid;
        private float smoothedHigh;

        synchronized void offer(byte[] stereo, int count, long pts) {
            offer(stereo, count, pts, 2);
        }

        synchronized void offerMono(byte[] mono, long pts) {
            offer(mono, mono.length / 2, pts, 1);
        }

        private void offer(byte[] pcm, int count, long pts, int channels) {
            if (end != 0 && Math.abs(pts - end) > 50_000) {
                clear();
            }
            int available = Math.min(count, pcm.length / (channels * 2));
            for (int i = 0; i < available; i++) {
                if (samples == 0) {
                    start = pts + (long) i * 1_000_000 / Pcm.RATE;
                }
                for (int channel = 0; channel < 2; channel++) {
                    int at = (i * channels + (channels == 1 ? 0 : channel)) * 2;
                    double value = (short) (pcm[at] & 0xff | pcm[at + 1] << 8) / 32768.0;
                    double lowValue = low[channel].apply(value);
                    double upperValue = upper[channel].apply(value);
                    level += value * value;
                    bass += lowValue * lowValue;
                    mid += (upperValue - lowValue) * (upperValue - lowValue);
                    high += (value - upperValue) * (value - upperValue);
                }
                if (++samples == WINDOW) {
                    finish(start + (long) WINDOW * 1_000_000 / Pcm.RATE);
                }
            }
            end = pts + (long) available * 1_000_000 / Pcm.RATE;
        }

        private void finish(long at) {
            double energy = Math.sqrt(bass / (WINDOW * 2));
            pulse *= 0.85f;
            if (energy > 0.035 && energy > baseline * 1.6 && (beat == 0 || at - beat >= 250_000)) {
                if (beat != 0 && at - beat <= 1_200_000) {
                    bpm += (60_000_000f / (at - beat) - bpm) * 0.25f;
                }
                beat = at;
                pulse = 1;
            }
            baseline += (energy - baseline) * 0.04;
            smoothedLevel = smooth(smoothedLevel, amplitude(level));
            smoothedBass = smooth(smoothedBass, amplitude(bass));
            smoothedMid = smooth(smoothedMid, amplitude(mid));
            smoothedHigh = smooth(smoothedHigh, amplitude(high));
            frames.addLast(new AudioFrame(at, smoothedLevel, smoothedBass, smoothedMid, smoothedHigh, pulse, bpm));
            while (frames.size() > 500) {
                frames.removeFirst();
            }
            samples = 0;
            level = bass = mid = high = 0;
        }

        private static float amplitude(double energy) {
            return (float) Math.min(1, Math.sqrt(energy / (WINDOW * 2)) * 2.5);
        }

        private static float smooth(float previous, float next) {
            return previous + (next - previous) * (next > previous ? 0.6f : 0.12f);
        }

        @Nullable
        synchronized AudioFrame sample(long at) {
            while (frames.size() > 1) {
                var iterator = frames.iterator();
                iterator.next();
                if (iterator.next().at > at) {
                    break;
                }
                frames.removeFirst();
            }
            AudioFrame frame = frames.peekFirst();
            return frame == null || frame.at > at || at - frame.at > 250_000 ? null : frame;
        }

        synchronized void clear() {
            frames.clear();
            samples = 0;
            start = end = beat = 0;
            level = bass = mid = high = baseline = 0;
            pulse = smoothedLevel = smoothedBass = smoothedMid = smoothedHigh = 0;
            bpm = 120;
            for (Filter filter : low) {
                filter.clear();
            }
            for (Filter filter : upper) {
                filter.clear();
            }
        }
    }

    private static final class Filter {
        private final double a;
        private final double b;
        private final double c;
        private final double d;
        private final double e;
        private double x1;
        private double x2;
        private double y1;
        private double y2;

        Filter(double cutoff) {
            double omega = 2 * Math.PI * cutoff / Pcm.RATE;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / Math.sqrt(2);
            a = (1 - cosine) / 2 / (1 + alpha);
            b = (1 - cosine) / (1 + alpha);
            c = a;
            d = -2 * cosine / (1 + alpha);
            e = (1 - alpha) / (1 + alpha);
        }

        double apply(double value) {
            double output = a * value + b * x1 + c * x2 - d * y1 - e * y2;
            x2 = x1;
            x1 = value;
            y2 = y1;
            y1 = output;
            return output;
        }

        void clear() {
            x1 = x2 = y1 = y2 = 0;
        }
    }

    record Colors(int left, int right, long at) {
    }

    static int[] colors(int width, int height, IntUnaryOperator pixel) {
        int[] result = new int[2];
        if (width <= 0 || height <= 0) {
            return result;
        }
        for (int side = 0; side < 2; side++) {
            int[] weights = new int[512];
            int[] reds = new int[512];
            int[] greens = new int[512];
            int[] blues = new int[512];
            for (int row = 0; row < 16; row++) {
                int y = (2 * row + 1) * height / 32;
                for (int column = 0; column < 12; column++) {
                    int x = (side * 24 + 2 * column + 1) * width / 48;
                    int color = pixel.applyAsInt(y * width + x);
                    int red = color >> 16 & 255;
                    int green = color >> 8 & 255;
                    int blue = color & 255;
                    int brightest = Math.max(red, Math.max(green, blue));
                    if (brightest < 16) {
                        continue;
                    }
                    int weight = brightest - Math.min(red, Math.min(green, blue)) + 32;
                    int bucket = (red >> 5) << 6 | (green >> 5) << 3 | blue >> 5;
                    weights[bucket] += weight;
                    reds[bucket] += red * weight;
                    greens[bucket] += green * weight;
                    blues[bucket] += blue * weight;
                }
            }
            int best = 0;
            for (int i = 1; i < weights.length; i++) {
                if (weights[i] > weights[best]) {
                    best = i;
                }
            }
            int weight = weights[best];
            result[side] = weight == 0 ? 0 : (reds[best] / weight) << 16
                    | (greens[best] / weight) << 8 | blues[best] / weight;
        }
        return result;
    }

    static int blend(int previous, int next, float amount) {
        int red = Math.round((previous >> 16 & 255) + ((next >> 16 & 255) - (previous >> 16 & 255)) * amount);
        int green = Math.round((previous >> 8 & 255) + ((next >> 8 & 255) - (previous >> 8 & 255)) * amount);
        int blue = Math.round((previous & 255) + ((next & 255) - (previous & 255)) * amount);
        return red << 16 | green << 8 | blue;
    }
}
