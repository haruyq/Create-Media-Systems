package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.p2p.P2pOverlay;
import org.haruyq.createmediasystems.stream.DiscUrl;
import org.junit.jupiter.api.Test;

class DiscStreamTest {
    @Test
    void readsShortBlurayPlaylistsAndRejectsPathsAndInvalidBounds() throws Exception {
        byte[] bytes = new byte[84];
        ByteBuffer data = ByteBuffer.wrap(bytes);
        data.put("MPLS0200".getBytes(StandardCharsets.US_ASCII)).putInt(40);
        data.position(40).putInt(40).putShort((short) 0).putShort((short) 1).putShort((short) 0);
        data.putShort((short) 32).put("00000M2TS".getBytes(StandardCharsets.US_ASCII));
        data.putInt(64, 600 * 45000).putInt(68, 632 * 45000);
        BlurayPlaylist playlist = BlurayPlaylist.parse(bytes, 0);
        assertEquals(32, playlist.duration());
        assertEquals("00000", playlist.clips().getFirst().id());
        data.putInt(68, 599 * 45000);
        assertThrows(IOException.class, () -> BlurayPlaylist.parse(bytes, 0));
        data.putInt(68, 632 * 45000);
        bytes[52] = '/';
        assertThrows(IOException.class, () -> BlurayPlaylist.parse(bytes, 0));
        bytes[52] = '0';
        data.putInt(40, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> BlurayPlaylist.parse(bytes, 0));
    }

    @Test
    void acceptsOnlyAnOpaqueOwnerAndSource() {
        DiscUrl url = new DiscUrl(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(url, DiscUrl.parse(url.toString()));
        for (String invalid : new String[]{"file:///E:/VIDEO_TS", "cms-disc://localhost/test", url + "?path=C:/secret",
                url + "/../../secret", url.toString().replace("//", "//user@"), url + "#extra"}) {
            assertNull(DiscUrl.parse(invalid), invalid);
        }
    }

    @Test
    void preservesTheWholeTimelineAcrossIndependentQualityChanges() {
        String playlist = DiscStream.playlist(10.5);
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertTrue(playlist.strip().endsWith("#EXT-X-ENDLIST"));
        assertFalse(playlist.contains("#EXT-X-DISCONTINUITY"));
        double length = 0;
        List<String> segments = new java.util.ArrayList<>();
        for (String line : playlist.split("\n")) {
            if (line.startsWith("#EXTINF:")) {
                length += Double.parseDouble(line.substring("#EXTINF:".length(), line.indexOf(',')));
            } else if (!line.isBlank() && !line.startsWith("#")) {
                segments.add(line);
            }
        }
        assertEquals(10.5, length, 1e-6, "its segments last as long as the disc");
        assertEquals(java.util.stream.IntStream.range(0, segments.size()).mapToObj(i -> i + ".ts").toList(), segments);
        assertThrows(IllegalArgumentException.class, () -> DiscStream.playlist(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> DiscStream.playlist(86400));
    }

    @Test
    void reducesQualityForASlowEncoderAndRecoversGradually() {
        long viewer = 13_000_000, second = 1_000_000_000L;
        DiscStream.Adaptive rate = new DiscStream.Adaptive();
        assertEquals(viewer, rate.budget(viewer), "a fast enough encoder keeps the viewer's budget");
        rate.encoded(2L * DiscSource.SEGMENT_SECONDS * second, viewer, 0);
        long reduced = rate.budget(viewer);
        DiscStream.Adaptive slower = new DiscStream.Adaptive();
        slower.encoded(4L * DiscSource.SEGMENT_SECONDS * second, viewer, 0);
        assertTrue(reduced < viewer, "a slow encoder lowers the budget");
        assertEquals(2, reduced / (double) slower.budget(viewer), 0.01, "twice as slow, half the budget");
        assertTrue(DiscSource.Quality.forBudget(reduced).height() < DiscSource.Quality.forBudget(viewer).height());
        rate.encoded(second, reduced, second);
        assertEquals(reduced, rate.budget(viewer), "one fast segment is not yet a recovery");
        long now = second;
        while (rate.budget(viewer) == reduced && now < 600 * second) {
            now += second;
            rate.encoded(second, reduced, now);
        }
        long stepped = rate.budget(viewer);
        assertTrue(stepped > reduced && stepped < viewer, "it recovers a step at a time, was " + stepped);
        rate.encoded(second, reduced, now + second);
        assertEquals(stepped, rate.budget(viewer), "not a step every segment");
        assertEquals(1_000_000, rate.budget(1_000_000), "never above what the viewer can take");
    }

    @Test
    void encodesADvdNoLargerThanItsOwnPicture() {
        DiscSource dvd = new DiscSource("ffmpeg", java.nio.file.Path.of("."), false, 1, 60, null);
        assertEquals(new DiscSource.Quality(480, 30, 2500), dvd.quality(13_000_000));
        assertEquals(new DiscSource.Quality(480, 30, 1100), dvd.quality(3_000_000), "a slower viewer's is the same pass");
        assertEquals(360, dvd.quality(1_000_000).height());
        DiscSource bluray = new DiscSource("ffmpeg", java.nio.file.Path.of("."), true, 1, 60, null);
        assertEquals(new DiscSource.Quality(1080, 60, 8000), bluray.quality(13_000_000));
    }

    @Test
    void tellsAnEncodersFailureFromADiscs() {
        assertNull(DiscSource.failure(0, "", null));
        assertInstanceOf(DiscSource.EncoderFailed.class, DiscSource.failure(1,
                "[h264_nvenc @ 000001] OpenEncodeSessionEx failed: out of memory (10)", null));
        IOException unread = DiscSource.failure(1, "[dvdvideo @ 000001] libdvdread: Can't open E: for reading", null);
        assertFalse(unread instanceof DiscSource.EncoderFailed, "an unreadable disc is no reason to encode on the CPU");
        assertFalse(DiscSource.failure(0, "The disc is encrypted", null) instanceof DiscSource.EncoderFailed);
    }

    @Test
    void refusesScrambledPesPackets() {
        assertTrue(DiscSource.scrambled(new byte[]{0, 0, 1, (byte) 0xe0, 0, 0, (byte) 0xb0}));
        assertFalse(DiscSource.scrambled(new byte[]{0, 0, 1, (byte) 0xe0, 0, 0, (byte) 0x80}));
        assertFalse(DiscSource.scrambled(new byte[]{0, 0, 1}));
    }

    @Test
    void aViewerSeesTheSegmentItWaitsForInTheP2pOverlay() {
        long s = 1_000_000_000L, now = 100 * s;
        var stats = new DiscStream.Stats(false, 38, now - 5 * s, 37, 4 * 1024 * 1024, 520_000_000, 1, "Disc transfer timed out",
                null, -1, null);
        List<P2pOverlay.Line> lines = stats.lines(false, now);
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).text().contains("38"), "the segment it waits for: " + lines.get(0));
        assertTrue(lines.get(1).text().contains("37"), "the last segment it had: " + lines.get(1));
        assertTrue(lines.get(2).text().contains("Disc transfer timed out"), "why the last request failed: " + lines.get(2));
        var brief = new DiscStream.Stats(false, 38, now - s / 10, 37, 4 * 1024 * 1024, 520_000_000, 1,
                "Disc transfer timed out", null, -1, null);
        assertNotEquals(brief.lines(false, now).getFirst().color(), lines.getFirst().color(),
                "past a segment's length the wait shows as a warning");
        assertTrue(stats.lines(true, now).isEmpty(), "a viewer's stream shows nothing to a host");
    }

    @Test
    void aHostSeesItsEncoderInTheP2pOverlay() {
        var stats = new DiscStream.Stats(true, 0, 0, -1, 0, 0, 0, null, "h264_nvenc", 12, "1080p60 8000 kbps");
        List<P2pOverlay.Line> lines = stats.lines(true, 0);
        assertEquals(1, lines.size(), lines.toString());
        for (String fact : List.of("h264_nvenc", "12", "1080p60 8000 kbps")) {
            assertTrue(lines.getFirst().text().contains(fact), fact + " in " + lines.getFirst());
        }
    }
}
