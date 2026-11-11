package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.haruyq.createmediasystems.ApiHttp;
import org.junit.jupiter.api.Test;

class UrlPolicyTest {
    private static final List<String> HOSTS = List.of("youtube.com", "youtu.be", "discordapp.com");

    @Test
    void allowsListedHostsAndSubdomainsOnly() {
        assertTrue(UrlPolicy.isAllowed("https://www.youtube.com/watch?v=abc", HOSTS));
        assertTrue(UrlPolicy.isAllowed("https://cdn.discordapp.com/attachments/1/2/a.mp3", HOSTS));
        assertFalse(UrlPolicy.isAllowed("https://evilyoutube.com/watch?v=abc", HOSTS));
        assertFalse(UrlPolicy.isAllowed("https://youtube.com.evil.net/x", HOSTS));
        assertFalse(UrlPolicy.isAllowed("file://youtube.com/etc/passwd", HOSTS));
        assertFalse(UrlPolicy.isAllowed("not a url", HOSTS));
    }

    @Test
    void buildsApiEndpoints() {
        assertEquals("http://api/stream?url=https%3A%2F%2Fyoutu.be%2Fabc%3Fsi%3Dz&format=ogg",
                ApiHttp.streamUrl("http://api/", "https://youtu.be/abc?si=z", 0, null));
        assertEquals("http://api/stream?url=https%3A%2F%2Fcdn.discordapp.com%2Fa.mp3&start=12.50&format=ogg",
                ApiHttp.streamUrl("http://api", " https://cdn.discordapp.com/a.mp3 ", 12.5, null));
        assertEquals("http://api/stream?url=https%3A%2F%2Fyoutu.be%2Fabc&start=1.00&channel=left&format=ogg",
                ApiHttp.streamUrl("http://api", "https://youtu.be/abc", 1, "left"));
        assertEquals("http://api/info?url=https%3A%2F%2Fyoutu.be%2Fabc",
                ApiHttp.endpoint("http://api", "info", "https://youtu.be/abc"));
    }

    @Test
    void rtspStillNeedsExplicitServerHostPermission() {
        assertTrue(UrlPolicy.isAllowed("rtsp://camera.test:8554/main", List.of("camera.test")));
        assertFalse(UrlPolicy.isAllowed("rtsp://camera.test.evil:8554/main", List.of("camera.test")));
        assertFalse(UrlPolicy.isAllowed("rtsp://camera.test:65536/main", List.of("camera.test")));
        assertFalse(UrlPolicy.isAllowed("file://camera.test/main", List.of("camera.test")));
    }
}
