package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

class ProviderUrlsTest {
    @Test
    void recognizesSupportedVideoAndSharePages() {
        for (String url : new String[] {
                "https://tv.cctv.com/live/cctv13/", "https://news.cctv.com/2024/video.shtml",
                "https://www.bilibili.com/video/BV13x41117TL?p=2", "https://b23.tv/Ab123",
                "https://www.bilibili.com/ja/video/BV1PRtf6UE9x/?p=2", "https://www.bilibili.com/en/video/BV123/",
                "https://live.bilibili.com/12345", "https://www.iwara.tv/video/publicfixture",
                "https://www.youtube.com/watch?v=abc", "https://youtu.be/abc", "https://tver.jp/episodes/ep123abc"
        }) {
            assertTrue(ProviderUrls.matches(URI.create(url)), url);
        }
    }

    @Test
    void normalizesLocalizedPagesWithoutLosingPartSelection() {
        org.junit.jupiter.api.Assertions.assertEquals(
                URI.create("https://www.bilibili.com/video/BV1PRtf6UE9x/?p=2"),
                ProviderUrls.canonical(URI.create("https://www.bilibili.com/ja/video/BV1PRtf6UE9x/?p=2")));
    }

    @Test
    void rejectsLookalikeHostsCredentialsAndOtherPages() {
        assertFalse(ProviderUrls.matches(null));
        for (String url : new String[] {
                "https://www.bilibili.com.evil.test/video/BV123", "https://evil.iwara.tv/video/foo",
                "https://iwara.tv/profile/123", "https://tv.cctv.com:8000/live/cctv1/",
                "https://user:pass@tv.cctv.com/live/cctv1/", "file://tv.cctv.com/live/cctv1/",
                "//tv.cctv.com/live/cctv1/"
        }) {
            assertFalse(ProviderUrls.matches(URI.create(url)), url);
        }
    }
}
