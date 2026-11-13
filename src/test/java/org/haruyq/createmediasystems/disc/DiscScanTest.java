package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import org.haruyq.createmediasystems.playback.MediaTap;
import org.junit.jupiter.api.Test;

class DiscScanTest {
    /** A tap that counts what it is given, and keeps the last times and a pixel of the last picture. */
    private static class Counted implements MediaTap {
        final AtomicLong samples = new AtomicLong(), soundAt = new AtomicLong(-1), pictureAt = new AtomicLong(-1);
        final AtomicInteger pictures = new AtomicInteger(), pixel = new AtomicInteger();

        @Override
        public void sound(byte[] pcm, int count, int channels, long pts) {
            assertEquals(2, channels);
            assertEquals(samples.get() * 1_000_000 / 48000, pts, "Sound is timed by the samples before it");
            samples.addAndGet(count);
            soundAt.set(pts);
        }

        @Override
        public void picture(int width, int height, IntUnaryOperator pixel, long at) {
            assertEquals(DiscScan.WIDTH, width);
            assertEquals(DiscScan.HEIGHT, height);
            this.pixel.set(pixel.applyAsInt(width * height - 1));
            pictures.incrementAndGet();
            pictureAt.set(at);
        }

        @Override
        public void clear() {
        }
    }

    /** Two seconds of a tone and a red picture, written the way DiscSource#scan has a disc written. */
    private static IntFunction<List<String>> twoSeconds(String picture) {
        return port -> List.of("ffmpeg", "-hide_banner", "-v", "error", "-nostdin",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-f", "lavfi", "-i", picture,
                "-map", "0:a", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", "-f", "s16le", "pipe:1",
                "-map", "1:v", "-vf", "fps=10:start_time=0,scale=96:54", "-pix_fmt", "rgb24", "-f", "rawvideo",
                "tcp://127.0.0.1:" + port);
    }

    @Test
    void givesATapTheSoundAndPicturesOfTheWholeTitleWithTheirTimes() throws Exception {
        assumeTrue(onPath("ffmpeg"), "FFmpeg is not on the PATH");
        Counted tap = new Counted();
        DiscScan.run(twoSeconds("color=c=red:s=320x180:r=30:d=2"), new byte[0], null, tap, new CompletableFuture<>());
        assertEquals(96_000, tap.samples.get());
        assertEquals(1_900_000, tap.soundAt.get());
        assertEquals(20, tap.pictures.get());
        assertEquals(1_900_000, tap.pictureAt.get());
        // Red, as near as its way through YUV leaves it.
        int pixel = tap.pixel.get();
        assertTrue(pixel >> 16 > 240 && (pixel >> 8 & 255) < 16 && (pixel & 255) < 16, "The pictures are RGB: " + Integer.toHexString(pixel));
    }

    @Test
    void aReaderThatFailsSaysSoRatherThanWaitingForItsPictures() {
        assumeTrue(onPath("ffmpeg"), "FFmpeg is not on the PATH");
        Counted tap = new Counted();
        assertThrows(IOException.class, () -> DiscScan.run(port -> List.of("ffmpeg", "-v", "error", "-nostdin", "-i",
                Path.of("no-such-disc").toAbsolutePath().toString(), "-f", "s16le", "pipe:1", "-f", "rawvideo",
                "tcp://127.0.0.1:" + port), new byte[0], null, tap, new CompletableFuture<>()));
        assertEquals(0, tap.pictures.get());
    }

    @Test
    void cancelledItStopsReading() throws Exception {
        assumeTrue(onPath("ffmpeg"), "FFmpeg is not on the PATH");
        CompletableFuture<Void> result = new CompletableFuture<>();
        Counted tap = new Counted() {
            @Override
            public void sound(byte[] pcm, int count, int channels, long pts) {
                super.sound(pcm, count, channels, pts);
                result.cancel(false);
            }
        };
        // An hour of it, read as fast as it comes: over at its first sound, or this would take minutes.
        try {
            DiscScan.run(port -> List.of("ffmpeg", "-hide_banner", "-v", "error", "-nostdin", "-re",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=3600", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=3600",
                    "-map", "0:a", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", "-f", "s16le", "pipe:1",
                    "-map", "1:v", "-vf", "fps=10,scale=96:54", "-pix_fmt", "rgb24", "-f", "rawvideo",
                    "tcp://127.0.0.1:" + port), new byte[0], null, tap, result);
        } catch (IOException stopped) {
            // FFmpeg, killed, says so: whoever cancelled it isn't told (see DiscScan#start).
        }
        assertTrue(tap.samples.get() <= 4800, "Nothing more is given once it is cancelled");
    }

    private static boolean onPath(String tool) {
        try {
            return new ProcessBuilder(tool, "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
