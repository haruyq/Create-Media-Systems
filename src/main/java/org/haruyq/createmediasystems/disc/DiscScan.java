package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.haruyq.createmediasystems.api.disc.DiscAccess;
import org.haruyq.createmediasystems.playback.MediaTap;
import org.haruyq.createmediasystems.playback.Pcm;

/**
 * A disc's whole title read once from its start, as fast as its drive reads and this computer decodes, for what
 * follows a track ahead of its playing (the stage lights' analysis; see DiscClient#decodeAhead): its sound as 48 kHz
 * stereo and small pictures a few times a second, given to a tap with their times in the title, as a cached video's
 * are (see CachedDecoder).
 *
 * <p>A reader of its own, beside the one that plays the disc: on a real drive the two seek against each other, so it
 * is for a disc that waits at its start, not one that plays.
 */
final class DiscScan {
    /** The pictures it gives: as CachedDecoder's. */
    static final int WIDTH = 96;
    static final int HEIGHT = 54;
    static final int FPS = 10;
    /** Samples of sound given at a time: a tenth of a second. */
    private static final int CHUNK = Pcm.RATE / 10;

    private DiscScan() {
    }

    /** Reads {@code disc} for {@code tap}; cancelling the result stops it. */
    static CompletableFuture<Void> start(DiscSource disc, MediaTap tap) {
        // ponytail: half the processors, so the game the disc is shared from still runs; a client setting if a
        // slow read is felt more than a slow game.
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("CMS disc scan").start(() -> {
            try {
                run(port -> disc.scan(WIDTH, HEIGHT, FPS, threads, port), disc.stdin(), disc.access(), tap, result);
                result.complete(null);
            } catch (Throwable failed) {
                result.completeExceptionally(failed);
            }
        });
        return result;
    }

    /**
     * Runs the FFmpeg {@code command} names, which writes sound to its output and pictures to the port of this
     * computer it is given (see DiscSource#scan), to its end or until {@code result} is done (cancelled).
     */
    static void run(IntFunction<List<String>> command, byte[] input, DiscAccess access, MediaTap tap,
                    CompletableFuture<Void> result) throws Exception {
        try (ServerSocket pictures = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var io = Executors.newVirtualThreadPerTaskExecutor()) {
            Process process;
            try {
                process = DiscSource.start(command.apply(pictures.getLocalPort()), access);
            } catch (IOException missing) {
                throw new IOException("FFmpeg could not start; set discFfmpegPath in cms-client.toml");
            }
            try {
                result.whenComplete((done, failed) -> process.destroyForcibly());
                // Ended before it took up its pictures' socket, nobody will: whoever waits for it there stops.
                process.onExit().thenRun(() -> {
                    try {
                        pictures.close();
                    } catch (IOException closed) {
                    }
                });
                io.submit(() -> {
                    try (var out = process.getOutputStream()) {
                        out.write(input);
                    }
                    return null;
                });
                var errors = io.submit(() -> {
                    byte[] data = process.getErrorStream().readNBytes(8192);
                    process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
                    return new String(data, StandardCharsets.UTF_8);
                });
                var seen = io.submit(() -> {
                    pictures(pictures, tap, result);
                    return null;
                });
                sound(process.getInputStream(), tap, result);
                Throwable lost = null;
                try {
                    seen.get();
                } catch (ExecutionException failed) {
                    lost = failed.getCause();
                }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("The disc reader did not stop at the end of the title");
                }
                // Why FFmpeg failed says more than the socket it left behind.
                IOException failure = DiscSource.failure(process.exitValue(), errors.get(), access);
                if (failure != null) {
                    throw failure;
                }
                if (lost != null) {
                    throw new IOException("The disc's pictures could not be read", lost);
                }
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static void sound(InputStream in, MediaTap tap, CompletableFuture<Void> result) throws IOException {
        byte[] pcm = new byte[CHUNK * 4];
        long samples = 0;
        for (int read; !result.isDone() && (read = in.readNBytes(pcm, 0, pcm.length)) >= 4;) {
            tap.sound(pcm, read / 4, 2, samples * 1_000_000 / Pcm.RATE);
            samples += read / 4;
        }
    }

    private static void pictures(ServerSocket server, MediaTap tap, CompletableFuture<Void> result) throws IOException {
        try (Socket socket = server.accept(); InputStream in = socket.getInputStream()) {
            byte[] rgb = new byte[WIDTH * HEIGHT * 3];
            for (long frame = 0; !result.isDone() && in.readNBytes(rgb, 0, rgb.length) == rgb.length; frame++) {
                tap.picture(WIDTH, HEIGHT, index -> (rgb[index * 3] & 255) << 16 | (rgb[index * 3 + 1] & 255) << 8
                        | rgb[index * 3 + 2] & 255, frame * 1_000_000 / FPS);
            }
        }
    }
}
