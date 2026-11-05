package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class LivePlaylistsTest {
    private static final String LIVE = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-KEY:METHOD=AES-128,URI="key/1"
            #EXTINF:4.004,
            s100.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key/1"
            #EXTINF:4.004,
            s101.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key/2"
            #EXTINF:4.004,
            s102.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key/2"
            #EXTINF:4.004,
            s103.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key/2"
            #EXTINF:4.004,
            s104.ts
            """;

    @Test
    void liveWindowEndsTwoSegmentsBeforeTheOriginsNewest() {
        String held = LivePlaylists.holdBack(LIVE, URI.create("https://cdn.test/live/variant.m3u8"));
        assertTrue(held.startsWith("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n"));
        assertTrue(held.contains("https://cdn.test/live/s102.ts"));
        assertFalse(held.contains("s103.ts"));
        assertFalse(held.contains("s104.ts"));
        assertEquals(3, held.lines().filter(l -> l.startsWith("#EXTINF")).count());
        assertTrue(held.contains("URI=\"https://cdn.test/live/key/2\""), "VLC fetches keys from the origin");
    }

    @Test
    void finishedPlaylistIsPassedOnWhole() {
        String vod = LIVE + "#EXT-X-ENDLIST\n";
        String held = LivePlaylists.holdBack(vod, URI.create("https://cdn.test/vod/v.m3u8"));
        assertEquals(5, held.lines().filter(l -> l.startsWith("#EXTINF")).count());
        assertTrue(held.endsWith("#EXT-X-ENDLIST\n"));
    }

    @Test
    void masterPointsVlcAtTheHeldBackPlaylistFetchedWithTheOriginsHeaders() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/live/variant.m3u8", exchange -> {
            boolean referred = "https://tver.jp/live/tbs".equals(exchange.getRequestHeaders().getFirst("Referer"));
            byte[] body = LIVE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(referred ? 200 : 403, referred ? body.length : -1);
            if (referred) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        origin.start();
        try {
            String variant = "http://127.0.0.1:" + origin.getAddress().getPort() + "/live/variant.m3u8";
            String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3859904,RESOLUTION=1280x720\n" + variant + "\n";
            String served = LivePlaylists.serve(master, Map.of("referer", "https://tver.jp/live/tbs"));
            String local = served.lines().filter(l -> l.startsWith("http://127.0.0.1")).findFirst().orElseThrow();
            assertNotEquals(variant, local);

            HttpResponse<String> playlist = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(local)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, playlist.statusCode());
            assertTrue(playlist.body().contains(variant.replace("variant.m3u8", "s102.ts")));
            assertFalse(playlist.body().contains("s104.ts"));
        } finally {
            origin.stop(0);
        }
    }
}
