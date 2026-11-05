package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExtensionUrlsTest {
    @Test
    void onlyExtensionPagesUseTheApiMediaRelay() {
        ExtensionUrls.useForTest("{\"urls\": [\"(?i:https?://media\\\\.example)/(?:live|ep/\\\\d+)/?\", \"(\"], \"live\": []}");
        try {
            assertTrue(StreamSound.usesApiAudio("https://media.example/live"));
            assertTrue(StreamSound.usesApiAudio("HTTPS://MEDIA.EXAMPLE/ep/12/"));
            assertFalse(StreamSound.usesApiAudio("rtsp://127.0.0.1:8554/synthetic"));
            assertFalse(StreamSound.usesApiAudio("https://www.bilibili.com/video/BV1synthetic"));
            assertFalse(StreamSound.usesApiAudio("https://media.example.evil.invalid/live"));
            assertFalse(StreamSound.usesApiAudio("invalid source"));
            assertFalse(StreamSound.usesApiAudio(null));
        } finally {
            ExtensionUrls.useForTest("{}");
        }
        assertFalse(StreamSound.usesApiAudio("https://media.example/live"));
    }
}
