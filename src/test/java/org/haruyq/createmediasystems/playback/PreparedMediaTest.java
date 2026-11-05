package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class PreparedMediaTest {
    @Test void rtspUsesSmallTimeBuffer() {
        var options = new NativeMediaResolver.Prepared(URI.create("rtsp://camera.test:8554/main"), true).options();
        assertTrue(options.contains(":network-caching=200"));
        assertTrue(options.contains(":rtsp-tcp"));
        assertFalse(new NativeMediaResolver.Prepared(URI.create("https://cdn.test/v.mp4"), false).options().contains(":rtsp-tcp"));
    }

    @Test void recordOpensTheAudioTrackAloneWithoutProbingVideoOrCaptions() throws Exception {
        // Unreachable hosts: probing the video's index or fetching a caption would fail the test.
        var response = com.google.gson.JsonParser.parseString("""
                {"mode": "native",
                 "video": {"kind": "video", "url": "https://video.invalid/v.mp4"},
                 "audio": [{"kind": "audio", "url": "https://audio.invalid/a.m4a",
                            "headers": {"Referer": "https://www.bilibili.com/"}}],
                 "subtitles": [{"format": "vtt", "url": "https://captions.invalid/c.vtt"}]}
                """).getAsJsonObject();
        var prepared = NativeMediaResolver.prepare(response, true);
        assertEquals(URI.create("https://audio.invalid/a.m4a"), prepared.uri());
        assertEquals(java.util.Map.of("referer", "https://www.bilibili.com/"), prepared.download(), "kept on this computer");
        var options = prepared.options();
        assertTrue(options.contains(":http-referrer=https://www.bilibili.com/"));
        assertFalse(options.stream().anyMatch(option -> option.startsWith(":input-slave")));
        assertTrue(response.has("video"), "the API's reply is left as it was");
    }

    @Test void leavesTheExtensionRelayAtVlcsDefaults() {
        assertTrue(NativeMediaResolver.Prepared.relay(URI.create("https://api.test/video?url=page&session=s")).options().isEmpty());
        assertTrue(new NativeMediaResolver.Prepared(URI.create("https://cdn.test/v.mp4"), false).options()
                .contains(":adaptive-logic=highest"));
    }

    @Test void probesBoundedMp4IndexAndRejectsTruncation() throws Exception {
        byte[] boxes = ByteBuffer.allocate(48).putInt(16).putInt(0x66747970).putLong(0)
                .putInt(24).putInt(0x73696478).putLong(0).putLong(0).putLong(0).array();
        assertArrayEquals(new long[]{16, 39}, NativeMediaResolver.sidx(boxes));
        assertThrows(java.io.IOException.class, () -> NativeMediaResolver.sidx(Arrays.copyOf(boxes, 30)));
    }
}
