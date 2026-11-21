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
    void aLinkToAPlaylistStillBeingAddedToOpensAsLive() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> files = Map.of(
                // A master lists its media playlists relative to itself, as AzuraCast's does.
                "/radio/live.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=211200,CODECS=\"mp4a.40.2\"\naac_hifi.m3u8\n",
                "/radio/aac_hifi.m3u8", LIVE,
                "/film/vod.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=211200\nmedia.m3u8\n",
                "/film/media.m3u8", LIVE + "#EXT-X-ENDLIST\n",
                "/page/not-a-playlist.m3u8", "<html>Not found</html>");
        origin.createContext("/", exchange -> {
            String file = files.get(exchange.getRequestURI().getPath());
            byte[] body = file == null ? new byte[0] : file.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(file == null ? 404 : 200, file == null ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            // VLC reports a live playlist as a seekable video as long as its segments: only this says it is live.
            assertTrue(MediaSources.web(URI.create(base + "/radio/live.m3u8"), false, 0).live(), "a master");
            assertTrue(MediaSources.web(URI.create(base + "/radio/aac_hifi.m3u8"), false, 0).live(), "a media playlist");
            assertFalse(MediaSources.web(URI.create(base + "/film/vod.m3u8"), false, 0).live(), "a finished one");
            assertFalse(MediaSources.web(URI.create(base + "/film/media.m3u8"), false, 0).live());
            assertFalse(MediaSources.web(URI.create(base + "/page/not-a-playlist.m3u8"), false, 0).live());
            assertFalse(MediaSources.web(URI.create(base + "/gone.m3u8"), false, 0).live(), "VLC is left to tell");
            assertFalse(MediaSources.web(URI.create(base + "/radio/radio.mp3"), false, 0).live(), "no playlist is asked for");
        } finally {
            origin.stop(0);
        }
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
