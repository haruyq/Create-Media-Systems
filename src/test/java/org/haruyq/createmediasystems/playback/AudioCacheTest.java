package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class AudioCacheTest {
    private static final URI SONG = URI.create("https://www.youtube.com/watch?v=publicfixture");
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    void downloadsInRangesThenPlaysTheCopyUntilItGoesUnplayedTooLong(@TempDir Path dir) throws Exception {
        byte[] audio = new byte[21 << 20]; // three requests: 10 + 10 + 1 MiB
        Arrays.fill(audio, (byte) 7);
        List<String> asked = new ArrayList<>();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/audio.m4a", exchange -> {
            asked.add(exchange.getRequestHeaders().getFirst("Range") + " " + exchange.getRequestHeaders().getFirst("Referer"));
            Matcher range = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(exchange.getRequestHeaders().getFirst("Range"));
            assertTrue(range.matches());
            int from = Integer.parseInt(range.group(1));
            int to = Math.min(Integer.parseInt(range.group(2)), audio.length - 1);
            exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + audio.length);
            exchange.sendResponseHeaders(206, to - from + 1);
            exchange.getResponseBody().write(audio, from, to - from + 1);
            exchange.close();
        });
        origin.start();
        try {
            AudioCache cache = new AudioCache(dir, () -> 7 * DAY, () -> 64L << 20);
            assertNull(cache.lookup(SONG), "nothing kept yet: it streams from the origin");
            URI media = URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/audio.m4a");
            cache.download(media, Map.of("referer", "https://www.youtube.com/"), AudioCache.name(SONG));
            assertEquals(3, asked.size());
            assertEquals("bytes=0-10485759 https://www.youtube.com/", asked.getFirst());
        } finally {
            origin.stop(0);
        }

        AudioCache cache = new AudioCache(dir, () -> 7 * DAY, () -> 64L << 20);
        URI kept = cache.lookup(SONG);
        assertNotNull(kept);
        assertArrayEquals(audio, Files.readAllBytes(Path.of(kept)));
        assertFalse(kept.toString().contains("youtube"), "no URL lands on disk");

        Files.setLastModifiedTime(Path.of(kept), FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY));
        assertNull(cache.lookup(SONG), "unplayed for longer than kept");
        assertFalse(Files.exists(Path.of(kept)));
    }

    @Test
    void pruneDropsTheLongestUnplayedUntilTheRestFitsAndLeftoverParts(@TempDir Path dir) throws Exception {
        AudioCache cache = new AudioCache(dir, () -> 7 * DAY, () -> 10);
        long now = System.currentTimeMillis();
        Path recent = write(dir, "recent.audio", 6, now);
        Path older = write(dir, "older.audio", 6, now - 1000);
        Path stale = write(dir, "stale.audio", 1, now - 8 * DAY);
        Path part = write(dir, "crashed.part", 1, now);
        cache.prune();
        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(older), "over the size: the longest unplayed goes");
        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(part), "no download of it is running");
    }

    private static Path write(Path dir, String name, int size, long modified) throws Exception {
        Path file = Files.write(dir.resolve(name), new byte[size]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }
}
