package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ExtensionUrlsTest {
    @Test
    void onlyExtensionPagesUseTheApiMediaRelay() {
        List<Pattern> urls = ExtensionUrls.use(
                "{\"urls\": [\"(?i:https?://media\\\\.example)/(?:live|ep/\\\\d+)/?\", \"(\"], \"live\": []}");
        assertEquals(1, urls.size(), "a pattern Java cannot read is left out");
        assertTrue(ExtensionUrls.matches(urls, URI.create("https://media.example/live")));
        assertTrue(ExtensionUrls.matches(urls, URI.create("HTTPS://MEDIA.EXAMPLE/ep/12/")));
        assertFalse(ExtensionUrls.matches(urls, URI.create("rtsp://127.0.0.1:8554/synthetic")));
        assertFalse(ExtensionUrls.matches(urls, URI.create("https://www.bilibili.com/video/BV1synthetic")));
        assertFalse(ExtensionUrls.matches(urls, URI.create("https://media.example.evil.invalid/live")));
        assertTrue(ExtensionUrls.use("{}").isEmpty());
        assertTrue(ExtensionUrls.use("not json").isEmpty());
    }

    @Test
    void anUnreadableSourceNeverGoesThroughTheRelay() {
        assertFalse(StreamSound.usesApiAudio("invalid source"));
        assertFalse(StreamSound.usesApiAudio(null));
    }
}
