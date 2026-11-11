package org.haruyq.createmediasystems.playback;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.client.ClientConfig;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * A track kept on this computer (see VideoCache), decoded ahead by VLC as fast as it can, its sound and pictures
 * offered to a tap with their own times: so what follows it (the stage lights) can know it from its first frame,
 * rather than as it plays.
 */
public final class CachedDecoder {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("CMS cached decoder").factory());
    /** Pictures offered (see MediaTap#picture): small and few, which is all a tap of their colours needs. */
    private static final int WIDTH = 96;
    private static final int HEIGHT = 54;
    /** Pictures it offers a second: each stands till the next. */
    public static final int FPS = 10;

    private CachedDecoder() {
    }

    /**
     * Decodes the copy of {@code track} kept here for a disc playing at most {@code discHeight} lines tall through at
     * most its first {@code maxMillis}, offering {@code tap} its stereo sound and its pictures; the result is then
     * {@code done}'s. Null if no copy is kept, or it is live or longer than {@code maxMillis}. Cancelling the result
     * stops the decoding.
     */
    @Nullable
    public static <T> CompletableFuture<T> decode(String track, int discHeight, long maxMillis, MediaTap tap,
                                                  Supplier<T> done) {
        NativeMediaResolver.Prepared cached = VideoCache.get().lookup(URI.create(track), ClientConfig.maxHeight(discHeight));
        if (cached == null || cached.live() || cached.duration() > maxMillis / 1000.0) {
            return null;
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            List<String> options = localOptions(cached);
            WORKER.execute(() -> {
                try {
                    decode(cached, options, new Span(maxMillis), tap, result::isCancelled);
                    result.complete(done.get());
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
            throw new IllegalArgumentException("Live media cannot be decoded ahead");
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
            throw new IllegalArgumentException("Decoding ahead requires an existing local cache file");
        }
    }

    private static void decode(NativeMediaResolver.Prepared prepared, List<String> options, Span span, MediaTap tap,
                               BooleanSupplier cancelled) throws InterruptedException {
        check(cancelled);
        if (Vlc.LIB == null || Vlc.INSTANCE == null) {
            throw new IllegalStateException(Vlc.MISSING);
        }
        if (Decoder.INSTANCE == null) {
            throw new IllegalStateException("VLC could not start the cache decoder");
        }
        try (Capture capture = new Capture(span, tap, cancelled)) {
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
                throw new IllegalStateException("VLC could not create the cache decoder");
            }
            try {
                if (Vlc.LIB.libvlc_media_player_play(player) != 0) {
                    throw new IllegalStateException("VLC could not decode the cached media");
                }
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
                while (true) {
                    check(cancelled);
                    if (capture.failed != null) {
                        throw new IllegalStateException("Cached decode failed", capture.failed);
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
                        throw new IllegalStateException("Cached decode timed out");
                    }
                    Thread.sleep(25);
                }
            } finally {
                Vlc.LIB.libvlc_media_player_stop(player);
                Vlc.LIB.libvlc_media_player_release(player);
            }
            check(cancelled);
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Cached decode cancelled");
        }
    }

    /** VLC decoding on as many threads as a player would steals from them: one, without the GPU. */
    private static final class Decoder {
        private static final Pointer INSTANCE = Vlc.instance("--avcodec-hw=none", "--avcodec-threads=1");
    }

    /** How far into its media a decode goes: through {@code maxMillis} from its first timestamp. */
    static final class Span {
        private final long maxMicros;
        private long origin = Long.MAX_VALUE;

        Span(long maxMillis) {
            maxMicros = maxMillis * 1000;
        }

        /** Whether {@code pts} (µs) is past it. */
        synchronized boolean beyond(long pts) {
            origin = Math.min(origin, pts);
            return pts - origin >= maxMicros;
        }
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
        final Span span;
        MediaTap tap;
        final BooleanSupplier cancelled;
        final Allocate audioBuffer = (opaque, target, size) -> allocate(target, size.longValue(), true);
        final Allocate videoBuffer = (opaque, target, size) -> allocate(target, size.longValue(), false);
        final AudioReady audio = (opaque, data, channels, rate, samples, bits, size, pts) -> {
            try {
                if (accept(pts) && channels == 2 && rate == Pcm.RATE && bits == 16
                        && samples > 0 && (long) samples * 4 <= size.longValue()) {
                    tap.sound(data.getByteArray(0, samples * 4), samples, 2, pts);
                }
            } catch (Throwable failure) {
                failed = failure;
            }
        };
        final VideoReady video = (opaque, data, width, height, bits, size, pts) -> {
            try {
                int rows = width <= 0 ? 0 : (int) Math.min(height, size.longValue() / ((long) width * 3));
                if (accept(pts) && bits == 24 && width > 0 && rows > 0) {
                    tap.picture(width, rows, index -> {
                        long at = (long) index * 3;
                        return (data.getByte(at) & 255) << 16 | (data.getByte(at + 1) & 255) << 8
                                | data.getByte(at + 2) & 255;
                    }, pts);
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

        Capture(Span span, MediaTap tap, BooleanSupplier cancelled) {
            this.span = span;
            this.tap = tap;
            this.cancelled = cancelled;
        }

        private boolean accept(long pts) {
            progress = System.nanoTime();
            if (cancelled.getAsBoolean() || failed != null || pts < 0) {
                return false;
            }
            if (span.beyond(pts)) {
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
            return "#transcode{vcodec=RV24,width=" + WIDTH + ",height=" + HEIGHT + ",fps=" + FPS
                    + ",acodec=s16l,channels=2,samplerate=" + Pcm.RATE + ",threads=1}"
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
}
