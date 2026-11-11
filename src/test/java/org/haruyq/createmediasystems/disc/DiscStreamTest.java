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
        assertTrue(playlist.contains("#EXTINF:2.500000,\n2.ts\n#EXT-X-ENDLIST"));
        assertFalse(playlist.contains("#EXT-X-DISCONTINUITY"));
        assertThrows(IllegalArgumentException.class, () -> DiscStream.playlist(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> DiscStream.playlist(86400));
    }

    @Test
    void reducesQualityForASlowEncoderAndRecoversGradually() {
        DiscStream.Adaptive rate = new DiscStream.Adaptive();
        assertEquals(new DiscSource.Quality(1080, 60, 8000), DiscSource.Quality.forBudget(rate.budget(13_000_000)));
        rate.encoded(6_000_000_000L, 13_000_000, 0);
        long reduced = rate.budget(13_000_000);
        assertEquals(6_500_000, reduced, "encoding at half speed halves the budget");
        assertEquals(720, DiscSource.Quality.forBudget(reduced).height());
        for (int i = 0; i < 20; i++) rate.encoded(1_000_000_000L, reduced, 10_000_000_000L);
        assertEquals(reduced, rate.budget(13_000_000), "held for 45 s");
        rate.encoded(1_000_000_000L, reduced, 46_000_000_000L);
        assertEquals(7_150_000, rate.budget(13_000_000));
        rate.encoded(1_000_000_000L, reduced, 50_000_000_000L);
        assertEquals(7_150_000, rate.budget(13_000_000), "then a step every 15 s");
        assertEquals(1_000_000, rate.budget(1_000_000));
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
        assertEquals(List.of(
                "Disc: waiting for segment 38 for 5.00 s",
                "Last segment 37: 4.0 MB in 0.52 s (64.5 Mbps)",
                "Failed requests: 1, last: Disc transfer timed out"), lines.stream().map(P2pOverlay.Line::text).toList());
        assertEquals(0xFFFF5555, lines.getFirst().color(), "past a segment's length the wait shows red");
        assertTrue(stats.lines(true, now).isEmpty(), "a viewer's stream shows nothing to a host");
    }

    @Test
    void aHostSeesItsEncoderInTheP2pOverlay() {
        var stats = new DiscStream.Stats(true, 0, 0, -1, 0, 0, 0, null, "h264_nvenc", 12, "1080p60 8000 kbps");
        assertEquals(List.of("Disc: h264_nvenc, segment 12 at 1080p60 8000 kbps"),
                stats.lines(true, 0).stream().map(P2pOverlay.Line::text).toList());
    }
}
