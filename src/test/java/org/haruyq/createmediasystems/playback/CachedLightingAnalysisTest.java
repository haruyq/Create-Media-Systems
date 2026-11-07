package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedLightingAnalysisTest {
    @TempDir
    Path temp;

    @Test
    void acceptsOnlyExistingLocalInputsAndCopiesOnlyTheLocalSlave() throws Exception {
        URI video = Files.createFile(temp.resolve("video.mp4")).toUri();
        URI audio = Files.createFile(temp.resolve("audio.m4a")).toUri();
        var prepared = new NativeMediaResolver.Prepared(video, false, null, 1,
                List.of(":input-slave=" + audio, ":sout=#http", ":http-referrer=https://example.com"));
        assertEquals(List.of(":input-slave=" + audio), CachedLightingAnalysis.localOptions(prepared));
        assertThrows(IllegalArgumentException.class, () -> CachedLightingAnalysis.localOptions(
                new NativeMediaResolver.Prepared(URI.create("https://example.com/video.mp4"), false)));
        assertThrows(IllegalArgumentException.class, () -> CachedLightingAnalysis.localOptions(
                new NativeMediaResolver.Prepared(video, false, null, 1, List.of(":input-slave=https://example.com/a"))));
        assertThrows(IllegalArgumentException.class, () -> CachedLightingAnalysis.localOptions(
                new NativeMediaResolver.Prepared(temp.resolve("missing.mp4").toUri(), false)));
        assertThrows(IllegalArgumentException.class, () -> CachedLightingAnalysis.localOptions(
                new NativeMediaResolver.Prepared(video, true)));
    }

    @Test
    void mergesSeparateTrackTimestampsWithoutAdvancingDelayedAudioOrVideo() {
        CachedLightingAnalysis.Timeline timeline = new CachedLightingAnalysis.Timeline();
        timeline.video(1_000_000, 0xff0000, 0x0000ff);
        timeline.video(1_100_000, 0xff0000, 0x0000ff);
        timeline.video(1_200_000, 0x00ff00, 0x0000ff);
        timeline.audio(tone(0.5), 1_300_000);
        var frames = timeline.finish();
        assertEquals(8, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i * 100, frames.get(i).millis());
        }
        assertFalse(frames.get(0).audio());
        assertFalse(frames.get(2).audio());
        assertTrue(frames.get(3).audio());
        assertTrue(frames.get(4).bass() > frames.get(4).high() * 2);
        assertEquals(0xff0000, frames.get(0).left());
        assertEquals(0x0000ff, frames.get(0).right());
        assertNotEquals(0x00ff00, frames.get(2).left(), "color transitions are smoothed");
        assertFalse(frames.get(6).video(), "an ended video track must not leave colors available forever");
    }

    @Test
    void boundsLongMediaAndCancelsBeforeLoadingVlc() throws Exception {
        CachedLightingAnalysis.Timeline timeline = new CachedLightingAnalysis.Timeline();
        assertFalse(timeline.beyondLimit(5_000_000));
        assertFalse(timeline.beyondLimit(5_000_000 + CachedLightingAnalysis.MAX_MILLIS * 1000 - 1));
        assertTrue(timeline.beyondLimit(5_000_000 + CachedLightingAnalysis.MAX_MILLIS * 1000));
        timeline.video(5_000_000, 0xffffff, 0xffffff);
        timeline.video(5_000_000 + CachedLightingAnalysis.MAX_MILLIS * 1000, 0xffffff, 0xffffff);
        var frames = timeline.finish();
        assertEquals(72_000, frames.size());
        assertEquals(CachedLightingAnalysis.MAX_MILLIS - 100, frames.getLast().millis());
        var prepared = new NativeMediaResolver.Prepared(Files.createFile(temp.resolve("cancelled.mp4")).toUri(), false);
        assertThrows(CancellationException.class, () -> CachedLightingAnalysis.analyze(prepared, () -> true).join());
    }

    @Test
    void decodesLocalNativeFixtureWithMediaRelativeTimestamps() throws Exception {
        String fixture = System.getProperty("cms.analysis.fixture", System.getenv("CMS_ANALYSIS_FIXTURE"));
        assumeTrue(fixture != null && !fixture.isBlank());
        String slave = System.getProperty("cms.analysis.audio", System.getenv("CMS_ANALYSIS_AUDIO"));
        List<String> options = slave == null || slave.isBlank() ? List.of()
                : List.of(":input-slave=" + Path.of(slave).toUri().toASCIIString());
        var prepared = new NativeMediaResolver.Prepared(Path.of(fixture).toUri(), false, null, 0, options);
        var frames = CachedLightingAnalysis.analyze(prepared, () -> false).get(2, TimeUnit.MINUTES);
        assertTrue(frames.size() >= 20, "use a fixture with at least two seconds of audio and video");
        assertEquals(0, frames.getFirst().millis());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i * 100, frames.get(i).millis());
        }
        assertTrue(frames.stream().anyMatch(frame -> frame.audio() && frame.level() > 0.01f));
        assertTrue(frames.stream().anyMatch(frame -> frame.video() && (frame.left() != 0 || frame.right() != 0)));
        String duration = System.getProperty("cms.analysis.duration", System.getenv("CMS_ANALYSIS_DURATION"));
        if (duration != null && !duration.isBlank()) {
            assertEquals(Double.parseDouble(duration) * 1000, frames.getLast().millis(), 1500);
        }
        long cancelAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
        var pending = CachedLightingAnalysis.analyze(prepared, () -> System.nanoTime() >= cancelAt);
        try {
            pending.get(10, TimeUnit.SECONDS);
            fail("Native analysis ignored cancellation");
        } catch (CancellationException expected) {
        } catch (ExecutionException expected) {
            assertInstanceOf(CancellationException.class, expected.getCause());
        }
    }

    private static byte[] tone(double seconds) {
        int count = (int) (Pcm.RATE * seconds);
        byte[] stereo = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 80 * i / Pcm.RATE) * 12000);
            stereo[i * 4] = stereo[i * 4 + 2] = (byte) value;
            stereo[i * 4 + 1] = stereo[i * 4 + 3] = (byte) (value >> 8);
        }
        return stereo;
    }
}
