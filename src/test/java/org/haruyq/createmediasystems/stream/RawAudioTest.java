package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.haruyq.createmediasystems.api.media.RawAudio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class RawAudioTest {
    @AfterEach
    void forgetClips() {
        AudioClips.serverStopped();
    }

    @Test
    void feedsAndClipsAreTheirOwnKindsWhateverTheHostList() {
        String feed = RawAudio.feed(ResourceLocation.fromNamespaceAndPath("some_mod", "voice/1"));
        assertEquals("cms-raw:some_mod:voice/1", feed);
        assertEquals(MediaKind.RAW, MediaKind.of(feed));
        assertTrue(UrlPolicy.isAllowed(feed, List.of()));
        assertNull(MediaKind.of("cms-raw:Not Valid"));
        assertNull(MediaKind.of("cms-raw:"));

        String clip = RawAudio.clip(new byte[] {1, 2, 3});
        assertEquals(MediaKind.CLIP, MediaKind.of(clip));
        assertTrue(UrlPolicy.isAllowed(clip, List.of()));
        assertNull(MediaKind.of("cms-clip:abc"));
        assertNull(MediaKind.of(clip.toUpperCase()));
    }

    @Test
    void aClipIsKeptByItsHashWithinItsLimits() {
        byte[] file = {4, 5, 6};
        String link = RawAudio.clip(file);
        assertEquals(link, RawAudio.clip(file.clone()));
        file[0] = 9; // kept as given, not as changed after
        assertArrayEquals(new byte[] {4, 5, 6}, AudioClips.get(link.substring(MediaKind.CLIP_SCHEME.length())));
        assertThrows(IllegalArgumentException.class, () -> RawAudio.clip(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> RawAudio.clip(new byte[RawAudio.MAX_CLIP_BYTES + 1]));
    }

    @Test
    void theLeastRecentlyKeptClipsGoPastTheTotal() {
        int each = RawAudio.MAX_CLIP_BYTES;
        long fits = AudioClips.MAX_TOTAL_BYTES / each;
        String first = RawAudio.clip(filled(each, 0));
        for (int i = 1; i <= fits; i++) {
            RawAudio.clip(filled(each, i));
        }
        assertNull(AudioClips.get(first.substring(MediaKind.CLIP_SCHEME.length())));
        String last = RawAudio.clip(filled(each, 99));
        assertArrayEquals(filled(each, 99), AudioClips.get(last.substring(MediaKind.CLIP_SCHEME.length())));
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
