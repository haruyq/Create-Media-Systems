package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoCacheTest {
    @TempDir
    Path dir;

    private void keep(URI page, int videoBytes, int audioBytes, long playedAt) throws Exception {
        String name = VideoCache.name(page, 1080);
        Files.write(dir.resolve(name + ".video.mp4"), new byte[videoBytes]);
        Files.write(dir.resolve(name + ".audio.m4a"), new byte[audioBytes]);
        Path meta = dir.resolve(name + ".meta");
        Files.writeString(meta, "{\"video\":\"" + name + ".video.mp4\",\"audio\":\"" + name + ".audio.m4a\",\"duration\":177}");
        Files.setLastModifiedTime(meta, FileTime.fromMillis(playedAt));
    }

    @Test
    void keptVideoPlaysWithItsAudioAndLength() throws Exception {
        URI page = URI.create("https://www.youtube.com/watch?v=a");
        keep(page, 10, 5, 1000);
        var prepared = new VideoCache(dir, () -> 1000).lookup(page, 1080);
        assertNotNull(prepared);
        assertEquals("file", prepared.uri().getScheme());
        assertEquals(177, prepared.duration());
        assertFalse(prepared.live());
        assertTrue(prepared.options().stream().anyMatch(o -> o.startsWith(":input-slave=file:")));
        assertTrue(Files.getLastModifiedTime(dir.resolve(VideoCache.name(page, 1080) + ".meta")).toMillis() > 1000,
                "counted as played");
        assertNull(new VideoCache(dir, () -> 1000).lookup(URI.create("https://www.youtube.com/watch?v=other"), 1080));
        assertNull(new VideoCache(dir, () -> 1000).lookup(page, 480), "a Blu-ray's copy is not a DVD's");
    }

    @Test
    void longestUnplayedGoesFirstAndLeftoversWithIt() throws Exception {
        URI old = URI.create("https://www.youtube.com/watch?v=old");
        URI recent = URI.create("https://www.youtube.com/watch?v=recent");
        keep(old, 60, 10, 1000);
        keep(recent, 60, 10, 2000);
        Files.write(dir.resolve("deadbeef.video.mp4"), new byte[5]); // an unfinished download's file
        Files.createDirectories(dir.resolve("deadbeef.part"));
        VideoCache cache = new VideoCache(dir, () -> 100);
        cache.prune();
        assertNull(cache.lookup(old, 1080));
        assertNotNull(cache.lookup(recent, 1080));
        try (var files = Files.list(dir)) {
            assertEquals(3, files.count(), "only the recent video's meta, video and audio remain");
        }
    }

    @Test
    void unknownLengthIsLeftToVlc() {
        assertEquals(0, VideoCache.parseDuration("NA"));
        assertEquals(177.5, VideoCache.parseDuration("177.5"));
    }

    @Test
    void aDownloadCountsItsVideoAndThenItsAudio() {
        VideoCache.Download download = new VideoCache.Download();
        download.accept("[youtube] dQw4w9WgXcQ: Downloading webpage");
        download.accept("CMS-PROGRESS downloading NA NA");
        // As yt-dlp printed it: its first estimate is only what it has.
        download.accept("CMS-PROGRESS downloading 712 712");
        assertEquals(0, download.fraction(), "not all of it yet");
        download.accept("CMS-PROGRESS downloading 450 900.0");
        assertEquals(0.5, download.fraction(), 1e-9);
        download.accept("CMS-PROGRESS finished 900 900");
        // The audio after it: counted once its size is known.
        download.accept("CMS-PROGRESS downloading 10 10");
        assertEquals(0.5, download.fraction(), 1e-9);
        download.accept("  CMS-PROGRESS downloading 50 100.0\r");
        assertEquals(0.95, download.fraction(), 1e-9);
    }
}
