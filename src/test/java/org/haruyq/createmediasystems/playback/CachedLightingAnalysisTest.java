package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private static final long MAX_MILLIS = 7_200_000;
    private static final int INTERVAL = 100;

    @Test
    void mergesSeparateTrackTimestampsWithoutAdvancingDelayedAudioOrVideo() {
        CachedLightingAnalysis.Timeline timeline = new CachedLightingAnalysis.Timeline(MAX_MILLIS, INTERVAL);
        timeline.video(1_000_000, 0xff0000, 0x0000ff);
        timeline.video(1_100_000, 0xff0000, 0x0000ff);
        timeline.video(1_200_000, 0x00ff00, 0x0000ff);
        timeline.audio(tone(0.5), 1_300_000);
        var samples = timeline.finish();
        assertEquals(8, samples.size(), "one every 100 ms through the end of the audio");
        assertFalse(samples.get(0).audio());
        assertFalse(samples.get(2).audio());
        assertTrue(samples.get(3).audio());
        assertTrue(samples.get(4).bass() > samples.get(4).high() * 2);
        assertEquals(0xff0000, samples.get(0).leftColor());
        assertEquals(0x0000ff, samples.get(0).rightColor());
        assertNotEquals(0x00ff00, samples.get(2).leftColor(), "color transitions are smoothed");
        assertFalse(samples.get(6).video(), "an ended video track must not leave colors available forever");
    }

    @Test
    void boundsLongMedia() {
        CachedLightingAnalysis.Timeline timeline = new CachedLightingAnalysis.Timeline(MAX_MILLIS, INTERVAL);
        assertFalse(timeline.beyondLimit(5_000_000));
        assertFalse(timeline.beyondLimit(5_000_000 + MAX_MILLIS * 1000 - 1));
        assertTrue(timeline.beyondLimit(5_000_000 + MAX_MILLIS * 1000));
        timeline.video(5_000_000, 0xffffff, 0xffffff);
        timeline.video(5_000_000 + MAX_MILLIS * 1000, 0xffffff, 0xffffff);
        assertEquals(MAX_MILLIS / INTERVAL, timeline.finish().size());
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
