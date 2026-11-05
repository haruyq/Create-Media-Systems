package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Live HLS media playlists, passed to VLC from this computer {@link #HELD_BACK} segments behind the origin's newest.
 * VLC 3 plays a live stream at its newest segment with a fixed second of buffer, fetching each new segment as soon
 * as it is listed, when the CDN may not have it yet; whenever one came late the video stood still for two seconds,
 * every few seconds (TVer). Only the playlist text passes through here: VLC fetches segments and keys from the origin.
 */
final class LivePlaylists {
    static final int HELD_BACK = 2;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");
    // ponytail: never evicted, a URL and two headers per live playlist resolved since start; evict by last request if it grows.
    private static final Map<String, Origin> ORIGINS = new ConcurrentHashMap<>();
    private static HttpServer server;

    private record Origin(URI uri, Map<String, String> headers) {
    }

    private LivePlaylists() {
    }

    /** {@code master} with each media playlist it lists (absolute URLs) served from here, held back. */
    static String serve(String master, Map<String, String> headers) throws IOException {
        URI local = start();
        Matcher attribute = URI_ATTRIBUTE.matcher("");
        List<String> lines = new ArrayList<>();
        for (String line : master.split("\n", -1)) {
            if (line.startsWith("#")) {
                line = attribute.reset(line).replaceAll(m -> Matcher.quoteReplacement(
                        "URI=\"" + local(local, m.group(1), headers) + "\""));
            } else if (!line.isBlank()) {
                line = local(local, line.strip(), headers);
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static String local(URI server, String origin, Map<String, String> headers) {
        String id = UUID.randomUUID().toString();
        ORIGINS.put(id, new Origin(URI.create(origin), headers));
        return server.resolve(id + ".m3u8").toString();
    }

    /**
     * {@code playlist}, fetched from {@code base}, without its newest {@code segments} (keeping at least one), and
     * every address in it absolute. A finished playlist (VOD) is passed on whole.
     */
    static String holdBack(String playlist, URI base) {
        List<String> header = new ArrayList<>();
        List<List<String>> media = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        boolean ended = false;
        for (String line : playlist.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                ended |= line.startsWith("#EXT-X-ENDLIST");
                line = URI_ATTRIBUTE.matcher(line).replaceAll(m -> Matcher.quoteReplacement(
                        "URI=\"" + base.resolve(m.group(1)) + "\""));
                // Tags before the first segment describe the playlist; the rest belong to the segment they precede.
                (media.isEmpty() && !line.startsWith("#EXTINF") && pending.isEmpty() ? header : pending).add(line);
            } else {
                pending.add(base.resolve(line.strip()).toString());
                media.add(pending);
                pending = new ArrayList<>();
            }
        }
        int keep = ended ? media.size() : Math.max(1, media.size() - HELD_BACK);
        StringBuilder out = new StringBuilder();
        header.forEach(line -> out.append(line).append('\n'));
        media.subList(0, Math.min(keep, media.size())).forEach(s -> s.forEach(line -> out.append(line).append('\n')));
        pending.forEach(line -> out.append(line).append('\n')); // trailing tags, such as EXT-X-ENDLIST
        return out.toString();
    }

    private static synchronized URI start() throws IOException {
        if (server == null) {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            created.createContext("/", LivePlaylists::handle);
            created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            // Its dispatcher thread is a daemon only if the thread starting it is: it must not keep the game open.
            Thread starter = Thread.ofPlatform().daemon().unstarted(created::start);
            starter.start();
            try {
                starter.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted starting the live playlist server", interrupted);
            }
            server = created;
        }
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            Origin origin = ORIGINS.get(path.substring(1).replaceFirst("\\.m3u8$", ""));
            byte[] body;
            int status;
            try {
                if (origin == null) {
                    throw new IOException("Unknown playlist");
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(origin.uri()).timeout(Duration.ofSeconds(10));
                origin.headers().forEach(request::header);
                HttpResponse<String> response = NativeMediaResolver.HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                body = holdBack(response.body(), response.uri()).getBytes(StandardCharsets.UTF_8);
                status = 200;
            } catch (IOException | RuntimeException failed) {
                body = new byte[0]; // VLC tries again at its next refresh
                status = 502;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                body = new byte[0];
                status = 503;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }
}
