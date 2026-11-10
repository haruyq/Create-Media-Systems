package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
}
