package org.haruyq.createmediasystems.playback;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.haruyq.createmediasystems.lighting.AnalysisFrame;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public final class CachedLightingAnalysis {
    static final long MAX_MILLIS = AnalysisFrame.MAX_DURATION;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("CMS cached lighting").factory());

    private CachedLightingAnalysis() {
    }

    public static CompletableFuture<List<AnalysisFrame>> analyze(NativeMediaResolver.Prepared prepared,
                                                                 BooleanSupplier cancelled) {
        CompletableFuture<List<AnalysisFrame>> result = new CompletableFuture<>();
        try {
            List<String> options = localOptions(prepared);
            WORKER.execute(() -> {
                try {
                    result.complete(decode(prepared, options, () -> result.isCancelled() || cancelled.getAsBoolean()));
                } catch (Throwable failed) {
                    result.completeExceptionally(failed);
                }
            });
        } catch (RuntimeException failed) {
            result.completeExceptionally(failed);
        }
        return result;
    }

    static List<String> localOptions(NativeMediaResolver.Prepared prepared) {
        local(prepared.uri());
        if (prepared.live()) {
            throw new IllegalArgumentException("Live media cannot be preanalyzed");
        }
        List<String> options = new ArrayList<>();
        for (String option : prepared.options()) {
            if (option.startsWith(":input-slave=")) {
                local(URI.create(option.substring(13)));
                options.add(option);
            }
        }
        return List.copyOf(options);
    }

    private static void local(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() != null
                || !Files.isRegularFile(Path.of(uri))) {
            throw new IllegalArgumentException("Lighting analysis requires an existing local cache file");
        }
    }

    private static List<AnalysisFrame> decode(NativeMediaResolver.Prepared prepared, List<String> options,
                                               BooleanSupplier cancelled) throws InterruptedException {
        check(cancelled);
        if (Vlc.LIB == null || Vlc.INSTANCE == null) {
            throw new IllegalStateException(Vlc.MISSING);
        }
        if (Decoder.INSTANCE == null) {
            throw new IllegalStateException("VLC could not start the cache analyzer");
        }
        try (Capture capture = new Capture(cancelled)) {
            Pointer media = Vlc.LIB.libvlc_media_new_location(Decoder.INSTANCE, prepared.uri().toASCIIString());
            if (media == null) {
                throw new IllegalStateException("VLC could not open the cached media");
            }
            Pointer player;
            try {
                for (String option : options) {
                    Vlc.LIB.libvlc_media_add_option(media, option);
                }
                Vlc.LIB.libvlc_media_add_option(media, ":sout=" + capture.output());
                Vlc.LIB.libvlc_media_add_option(media, ":no-sout-keep");
                Vlc.LIB.libvlc_media_add_option(media, ":no-sub-autodetect-file");
                Vlc.LIB.libvlc_media_add_option(media, ":no-metadata-network-access");
                Vlc.LIB.libvlc_media_add_option(media, ":avcodec-hw=none");
                Vlc.LIB.libvlc_media_add_option(media, ":avcodec-threads=1");
                player = Vlc.LIB.libvlc_media_player_new_from_media(media);
            } finally {
                Vlc.LIB.libvlc_media_release(media);
            }
            if (player == null) {
                throw new IllegalStateException("VLC could not create the cache analyzer");
            }
            try {
                if (Vlc.LIB.libvlc_media_player_play(player) != 0) {
                    throw new IllegalStateException("VLC could not analyze the cached media");
                }
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
                while (true) {
                    check(cancelled);
                    if (capture.failed != null) {
                        throw new IllegalStateException("Cached lighting decode failed", capture.failed);
                    }
                    int state = Vlc.LIB.libvlc_media_player_get_state(player);
                    if (state == Vlc.Lib.ERROR) {
                        throw new IllegalStateException("VLC failed to decode the cached media");
                    }
                    if (state == Vlc.Lib.ENDED || capture.limit) {
                        break;
                    }
                    long now = System.nanoTime();
                    if (now > deadline || now - capture.progress > TimeUnit.SECONDS.toNanos(30)) {
                        throw new IllegalStateException("Cached lighting analysis timed out");
                    }
                    Thread.sleep(25);
                }
            } finally {
                Vlc.LIB.libvlc_media_player_stop(player);
                Vlc.LIB.libvlc_media_player_release(player);
            }
            check(cancelled);
            return capture.timeline.finish();
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Cached lighting analysis cancelled");
        }
    }

    private static final class Decoder {
        private static final String[] OPTIONS = {"--quiet", "--no-metadata-network-access", "--avcodec-hw=none",
                "--avcodec-threads=1"};
        private static final Pointer INSTANCE = Vlc.LIB.libvlc_new(OPTIONS.length, OPTIONS);
    }

    public static final class Size extends IntegerType {
        public Size() {
            super(Native.SIZE_T_SIZE, 0, true);
        }
    }

    public interface Allocate extends Callback {
        void invoke(Pointer opaque, Pointer target, Size size);
    }

    public interface AudioReady extends Callback {
        void invoke(Pointer opaque, Pointer data, int channels, int rate, int samples, int bits, Size size, long pts);
    }

    public interface VideoReady extends Callback {
        void invoke(Pointer opaque, Pointer data, int width, int height, int bits, Size size, long pts);
    }

    private static final class Capture implements AutoCloseable {
        final Timeline timeline = new Timeline();
        final BooleanSupplier cancelled;
        final Allocate audioBuffer = (opaque, target, size) -> allocate(target, size.longValue(), true);
        final Allocate videoBuffer = (opaque, target, size) -> allocate(target, size.longValue(), false);
        final AudioReady audio = (opaque, data, channels, rate, samples, bits, size, pts) -> {
            try {
                if (accept(pts) && channels == 2 && rate == Pcm.RATE && bits == 16
                        && samples > 0 && (long) samples * 4 <= size.longValue()) {
                    timeline.audio(data.getByteArray(0, samples * 4), pts);
                }
            } catch (Throwable failure) {
                failed = failure;
            }
        };
        final VideoReady video = (opaque, data, width, height, bits, size, pts) -> {
            try {
                int rows = width <= 0 ? 0 : (int) Math.min(height, size.longValue() / ((long) width * 3));
                if (accept(pts) && bits == 24 && width > 0 && rows > 0) {
                    int[] colors = LightingMedia.colors(width, rows, index -> {
                        long at = (long) index * 3;
                        return (data.getByte(at) & 255) << 16 | (data.getByte(at + 1) & 255) << 8
                                | data.getByte(at + 2) & 255;
                    });
                    timeline.video(pts, colors[0], colors[1]);
                }
            } catch (Throwable failure) {
                failed = failure;
            }
        };
        volatile Throwable failed;
        volatile boolean limit;
        volatile long progress = System.nanoTime();
        private Memory audioMemory;
        private Memory videoMemory;

        Capture(BooleanSupplier cancelled) {
            this.cancelled = cancelled;
        }

        private boolean accept(long pts) {
            progress = System.nanoTime();
            if (cancelled.getAsBoolean() || failed != null || pts < 0) {
                return false;
            }
            if (timeline.beyondLimit(pts)) {
                limit = true;
                return false;
            }
            return true;
        }

        private synchronized void allocate(Pointer target, long size, boolean audio) {
            try {
                if (size <= 0 || size > 4 * 1024 * 1024) {
                    throw new IllegalArgumentException("Unexpected cached decode buffer size");
                }
                Memory memory = audio ? audioMemory : videoMemory;
                if (memory == null || memory.size() < size) {
                    if (memory != null) {
                        memory.close();
                    }
                    memory = new Memory(size);
                    if (audio) {
                        audioMemory = memory;
                    } else {
                        videoMemory = memory;
                    }
                }
                target.setPointer(0, memory);
            } catch (Throwable failure) {
                failed = failure;
                target.setPointer(0, null);
            }
        }

        String output() {
            return "#transcode{vcodec=RV24,width=96,height=54,fps=10,acodec=s16l,channels=2,samplerate=48000,threads=1}"
                    + ":smem{no-time-sync,audio-prerender-callback=" + address(audioBuffer)
                    + ",audio-postrender-callback=" + address(audio) + ",video-prerender-callback=" + address(videoBuffer)
                    + ",video-postrender-callback=" + address(video) + "}";
        }

        private static long address(Callback callback) {
            return Pointer.nativeValue(CallbackReference.getFunctionPointer(callback));
        }

        @Override
        public void close() {
            if (audioMemory != null) {
                audioMemory.close();
            }
            if (videoMemory != null) {
                videoMemory.close();
            }
        }
    }

    static final class Timeline {
        private final LightingMedia.Audio analyzer = new LightingMedia.Audio();
        private final TreeMap<Long, LightingMedia.AudioFrame> audio = new TreeMap<>();
        private final TreeMap<Long, int[]> video = new TreeMap<>();
        private long origin = Long.MAX_VALUE;
        private long end;
        private long nextAudio;

        synchronized boolean beyondLimit(long pts) {
            origin = Math.min(origin, pts);
            return pts - origin >= MAX_MILLIS * 1000;
        }

        synchronized void audio(byte[] pcm, long pts) {
            origin = Math.min(origin, pts);
            int samples = pcm.length / 4;
            for (int offset = 0; offset < samples; offset += Pcm.RATE / 50) {
                int count = Math.min(Pcm.RATE / 50, samples - offset);
                byte[] window = java.util.Arrays.copyOfRange(pcm, offset * 4, (offset + count) * 4);
                long at = pts + (long) offset * 1_000_000 / Pcm.RATE;
                analyzer.offer(window, count, at);
                long through = at + (long) count * 1_000_000 / Pcm.RATE;
                LightingMedia.AudioFrame frame = analyzer.sample(through);
                if (frame != null && frame.at() >= nextAudio) {
                    audio.put(frame.at(), frame);
                    nextAudio = frame.at() + 100_000;
                }
            }
            end = Math.max(end, pts + (long) samples * 1_000_000 / Pcm.RATE);
        }

        synchronized void video(long pts, int left, int right) {
            origin = Math.min(origin, pts);
            video.put(pts, new int[]{left, right});
            end = Math.max(end, pts + 100_000);
        }

        synchronized List<AnalysisFrame> finish() {
            if (origin == Long.MAX_VALUE || audio.isEmpty() && video.isEmpty()) {
                throw new IllegalStateException("The cached media produced no lighting frames");
            }
            List<AnalysisFrame> frames = new ArrayList<>();
            long duration = Math.min(MAX_MILLIS, Math.max(1, (end - origin + 999) / 1000));
            int left = 0;
            int right = 0;
            boolean previousVideo = false;
            for (long millis = 0; millis < duration; millis += AnalysisFrame.INTERVAL) {
                long at = origin + millis * 1000;
                Map.Entry<Long, LightingMedia.AudioFrame> sound = audio.floorEntry(at);
                if (sound == null && !audio.isEmpty() && audio.firstKey() - at <= 20_000) {
                    sound = audio.firstEntry();
                }
                LightingMedia.AudioFrame a = sound == null || at - sound.getKey() > 250_000 ? null : sound.getValue();
                Map.Entry<Long, int[]> color = video.floorEntry(at);
                boolean visible = color != null && at - color.getKey() <= 250_000;
                if (visible) {
                    left = previousVideo ? LightingMedia.blend(left, color.getValue()[0], 0.4f) : color.getValue()[0];
                    right = previousVideo ? LightingMedia.blend(right, color.getValue()[1], 0.4f) : color.getValue()[1];
                }
                frames.add(new AnalysisFrame(millis, a == null ? 0 : a.level(), a == null ? 0 : a.bass(),
                        a == null ? 0 : a.mid(), a == null ? 0 : a.high(), a == null ? 0 : a.pulse(),
                        a == null ? 120 : a.bpm(), visible ? left : 0, visible ? right : 0, a != null, visible));
                previousVideo = visible;
            }
            double phase = 0;
            for (int i = 0; i < frames.size(); i++) {
                AnalysisFrame frame = frames.get(i);
                if (i > 0) {
                    AnalysisFrame previous = frames.get(i - 1);
                    phase += (frame.millis() - previous.millis()) / 1000.0 * previous.motionSpeed();
                }
                frames.set(i, frame.phase(phase));
            }
            return List.copyOf(frames);
        }
    }
}
