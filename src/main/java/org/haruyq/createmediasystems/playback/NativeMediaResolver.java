package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.stream.ProviderUrls;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

/** The API resolves metadata only. VLC and bounded SIDX probes connect to the origin from this client. */
final class NativeMediaResolver {
    /** The client's HTTP for resolving, caching and the API (StreamSound keeps its own, on HTTP/1.1). */
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int PROBE_LIMIT = 2 * 1024 * 1024;
    /**
     * How long a resolution is reused: walking in and out of a jukebox's range, a restart, or another jukebox playing
     * the same disc then skip the API and the SIDX probes. ponytail: one fixed lifetime, well inside what the origins
     * sign URLs for; read each URL's own expiry if a cached one is ever refused (VLC failing also drops it, see forget).
     */
    static final long CACHE_NANOS = 3 * 60 * 1_000_000_000L;
    // ponytail: expired entries go only when their key is asked for again; sweep them in resolve if that grows.
    private static final Map<Key, Cached> CACHE = new ConcurrentHashMap<>();
    /**
     * {@code download}: the headers to fetch {@code uri} with, when it is a record's whole audio in one file.
     * {@code duration}: the length in seconds, when the resolver knows it is a video on demand that long (yt-dlp);
     * 0 leaves VLC to tell, from what it can see once it opens it (see NativeAudio#classifyLive).
     */
    record Prepared(URI uri, boolean live, @Nullable Map<String, String> download, double duration, List<String> options) {
        Prepared(URI uri, boolean live) {
            this(uri, live, null, 0, defaults(uri, List.of()));
        }

        /** {@code resolved}: the VLC options the resolver found it needs (origin headers, captions), on top of CMS's own. */
        static Prepared of(URI uri, boolean live, @Nullable Map<String, String> download, double duration,
                           List<String> resolved) {
            return new Prepared(uri, live, download, duration, defaults(uri, resolved));
        }

        /** The API's relay of an extension's page, which plays with VLC's defaults. */
        static Prepared relay(URI uri) {
            return new Prepared(uri, true, null, 0, List.of());
        }

        private static List<String> defaults(URI media, List<String> resolved) {
            List<String> options = new ArrayList<>();
            options.add(":adaptive-logic=highest");
            if (UrlPolicy.isRtsp(media)) {
                options.add(":network-caching=200");
                options.add(":live-caching=200");
                options.add(":rtsp-tcp");
                // Allocation size, not a time buffer: accommodates large high-resolution I-frames.
                options.add(":rtsp-frame-buffer-size=4194304");
            }
            options.addAll(resolved);
            return List.copyOf(options);
        }
    }
    private record Key(URI source, boolean audioOnly, int maxHeight) {}
    private record Cached(CompletableFuture<Prepared> prepared, long expiresAt) {}
    private NativeMediaResolver() {}

    /**
     * {@code source}'s media, resolved by this client in the last few minutes or now; callers asking while it is
     * being resolved wait for that one resolution. {@code audioOnly} (a record) leaves out the video track and captions;
     * a video is at most {@code maxHeight} tall (0 for any).
     */
    static Prepared resolve(URI source, boolean audioOnly, int maxHeight) {
        Key key = new Key(source, audioOnly, maxHeight);
        CompletableFuture<Prepared> mine = new CompletableFuture<>();
        long now = System.nanoTime();
        Cached cached = CACHE.compute(key, (k, old) -> old != null && now - old.expiresAt() < 0 ? old
                : new Cached(mine, now + CACHE_NANOS));
        if (cached.prepared() != mine) {
            try {
                return cached.prepared().join();
            } catch (CompletionException failed) {
                throw failed.getCause() instanceof RuntimeException cause ? cause : failed;
            }
        }
        try {
            Prepared prepared = fetch(source, audioOnly, maxHeight);
            mine.complete(prepared);
            return prepared;
        } catch (RuntimeException failed) {
            CACHE.remove(key, cached);
            mine.completeExceptionally(failed);
            throw failed;
        }
    }

    /** Whether {@code source} is resolved, or being resolved, here already (see {@link #resolve}). */
    static boolean cached(URI source, boolean audioOnly, int maxHeight) {
        Cached cached = CACHE.get(new Key(source, audioOnly, maxHeight));
        return cached != null && System.nanoTime() - cached.expiresAt() < 0;
    }

    /** Forgets what {@code source} resolved to, so the next play resolves it again (VLC could not open it). */
    static void forget(URI source) {
        CACHE.keySet().removeIf(key -> key.source().equals(source));
    }

    /**
     * A display's media comes from yt-dlp on this computer if it can, else from the API; a record's from the API (one
     * file, which can be kept: see AudioCache), else from yt-dlp.
     */
    private static Prepared fetch(URI source, boolean audioOnly, int maxHeight) {
        if (!audioOnly) {
            try {
                return YtDlp.resolve(source, maxHeight);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Native media loading was interrupted");
            } catch (IOException | RuntimeException failed) {
                LogUtils.getLogger().warn("yt-dlp could not resolve {}: {}", source.getHost(), failed.getMessage());
            }
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(UrlPolicy.endpoint(ServerConfig.API_URL.get(), "resolve",
                    ProviderUrls.canonical(source).toString()))).timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json").build();
            HttpResponse<String> reply = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() != 200) throw new IOException("Native URL resolution failed (HTTP " + reply.statusCode() + ")");
            JsonObject response = JsonParser.parseString(reply.body()).getAsJsonObject();
            if (!NativeMedia.text(response, "mode", "").equals("native")) throw new IOException("This source requires the extension relay");
            return prepare(response, audioOnly);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native media loading was interrupted");
        } catch (IOException | RuntimeException error) {
            // A page, signed media URL or native exception may contain credentials. Do not attach it to log output.
            if (audioOnly) {
                // The API is down or refuses the track: a record or tape plays on from yt-dlp here, as a display does.
                LogUtils.getLogger().warn("The API could not resolve {} ({}); trying yt-dlp", source.getHost(),
                        error.getClass().getSimpleName());
                try {
                    return YtDlp.resolveAudio(source);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Native media loading was interrupted");
                } catch (IOException | RuntimeException failed) {
                    LogUtils.getLogger().warn("yt-dlp could not resolve {}: {}", source.getHost(), failed.getMessage());
                }
            }
            throw new IllegalStateException("Native media could not be resolved; check the API and origin availability");
        }
    }

    static Prepared prepare(JsonObject response, boolean audioOnly) throws IOException, InterruptedException {
        boolean hasManifest = response.has("manifest") && !response.get("manifest").isJsonNull();
        if (audioOnly && !hasManifest && response.has("video") && !response.get("video").isJsonNull()
                && !NativeMedia.text(response.getAsJsonObject("video"), "kind", "").equals("muxed")
                && response.has("audio") && !response.getAsJsonArray("audio").isEmpty()) {
            // A record plays no video, and only the chosen language (the API lists it first): one audio track opens
            // directly, with no SIDX probe, and can be kept (see AudioCache).
            response = response.deepCopy();
            response.remove("video");
            JsonArray chosen = new JsonArray();
            chosen.add(response.getAsJsonArray("audio").get(0));
            response.add("audio", chosen);
        }
        if (response.has("available_at")) {
            long waitMillis = Math.round(response.get("available_at").getAsDouble() * 1000) - System.currentTimeMillis();
            if (waitMillis > 30_000) throw new IOException("The origin stream is not available yet");
            if (waitMillis > 0) Thread.sleep(waitMillis);
        }
        List<JsonObject> tracks = NativeMedia.tracks(response);
        List<String> options = new ArrayList<>(NativeMedia.httpOptions(tracks));
        boolean live = response.has("live") && response.get("live").getAsBoolean();
        URI media;
        Map<String, String> download = null;
        if (hasManifest) {
            JsonObject manifest = response.getAsJsonObject("manifest");
            if (!NativeMedia.text(manifest, "type", "").equals("hls")) throw new IOException("Unsupported native manifest");
            String content = NativeMedia.text(manifest, "content", "");
            media = manifest(live ? LivePlaylists.serve(content, NativeMedia.httpHeaders(tracks)) : content, ".m3u8");
        } else if (tracks.size() == 1 || tracks.stream().anyMatch(t -> NativeMedia.text(t, "kind", "").equals("muxed"))) {
            if (tracks.isEmpty()) throw new IOException("The source has no playable tracks");
            media = NativeMedia.origin(tracks.getFirst());
            if (audioOnly && !live && NativeMedia.text(tracks.getFirst(), "kind", "").equals("audio")) {
                download = NativeMedia.httpHeaders(tracks);
            }
        } else {
            for (JsonObject track : tracks) ensureIndex(track);
            media = manifest(NativeMedia.dash(response), ".mpd");
        }
        if (!audioOnly) attachSubtitles(response, options);
        // The API's length: VLC has none for an HLS playlist until it has played a little, and without one a track
        // counts as live, never sought to where its listeners are (a tape played on, a paused disc) nor kept there.
        double duration = !live && response.has("duration") && response.get("duration").isJsonPrimitive()
                ? response.get("duration").getAsDouble() : 0;
        return Prepared.of(media, live, download, Double.isFinite(duration) && duration > 0 ? duration : 0, options);
    }

    private static URI manifest(String content, String suffix) throws IOException {
        if (content.length() > 1_048_576) throw new IOException("Native manifest is too large");
        Path file = Files.createTempFile("create_media_systems-native-", suffix);
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toUri();
    }

    private static void attachSubtitles(JsonObject response, List<String> options) throws IOException, InterruptedException {
        if (!response.has("subtitles")) return;
        List<String> slaves = new ArrayList<>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        int attempts = 0;
        for (var entry : response.getAsJsonArray("subtitles")) {
            if (++attempts > 8 || System.nanoTime() >= deadline) break;
            try {
                JsonObject subtitle = entry.getAsJsonObject();
                String format = NativeMedia.text(subtitle,"format", "");
                String suffix = switch (format) { case "bilibili_json" -> ".vtt"; case "vtt", "srt", "ass", "ssa", "ttml" -> "." + format; default -> null; };
                if (suffix == null) continue;
                HttpRequest.Builder request = HttpRequest.newBuilder(NativeMedia.origin(subtitle))
                        .timeout(Duration.ofMillis(Math.max(1,Math.min(2000,(deadline-System.nanoTime())/1_000_000))));
                if (subtitle.has("headers")) subtitle.getAsJsonObject("headers").entrySet().forEach(header -> {
                    if (NativeMedia.forwarded(header.getKey()))
                        request.header(header.getKey(), header.getValue().getAsString());
                });
                byte[] body = probe(request.build(), 200);
                if (body.length >= PROBE_LIMIT) continue;
                String content = new String(body, StandardCharsets.UTF_8);
                if (format.equals("bilibili_json")) content = NativeMedia.bilibiliVtt(content);
                slaves.add(manifest(content, suffix).toASCIIString());
            } catch (IOException | RuntimeException failure) {
                // Missing optional captions must not restart or interrupt the video.
            }
        }
        if (!slaves.isEmpty()) options.add(":input-slave=" + String.join("#", slaves));
    }

    private static void ensureIndex(JsonObject track) throws IOException, InterruptedException {
        if (track.has("segment_list")) return;
        URI origin = NativeMedia.origin(track);
        HttpRequest.Builder request = HttpRequest.newBuilder(origin).timeout(Duration.ofSeconds(20))
                .header("Range", "bytes=0-262143");
        if (track.has("headers")) track.getAsJsonObject("headers").entrySet().forEach(entry -> {
            String name = entry.getKey();
            // Unlike VLC, the probe can send Origin too.
            if (NativeMedia.forwarded(name) || name.equalsIgnoreCase("Origin"))
                request.header(name, entry.getValue().getAsString());
        });
        byte[] body = probe(request.build(), 200, 206);
        long[] range = sidx(body);
        track.addProperty("index_range", range[0] + "-" + range[1]);
        track.addProperty("init_range", "0-" + (range[0] - 1));
        track.add("segment_list", NativeMedia.segmentList(body, range[0], range[1]));
    }

    /**
     * At most {@link #PROBE_LIMIT} bytes of {@code request}'s body, which must come with one of {@code ok}. Closing
     * the body cancels the rest, so even a server ignoring Range cannot make a probe download the full movie.
     */
    private static byte[] probe(HttpRequest request, int... ok) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (Arrays.stream(ok).noneMatch(status -> status == response.statusCode())) {
                throw new IOException("Origin request failed (HTTP " + response.statusCode() + ")");
            }
            return in.readNBytes(PROBE_LIMIT);
        }
    }

    static long[] sidx(byte[] bytes) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(bytes);
        while (data.remaining() >= 8) {
            int offset = data.position();
            long size = Integer.toUnsignedLong(data.getInt());
            int type = data.getInt();
            int header = 8;
            if (size == 1) {
                if (data.remaining() < 8) break;
                size = data.getLong(); header = 16;
            }
            if (size < header || size > bytes.length - offset) break;
            if (type == 0x73696478 && offset > 0) return new long[] {offset, offset + size - 1};
            data.position((int)(offset + size));
        }
        throw new IOException("Origin did not provide a bounded MP4 segment index");
    }
}
