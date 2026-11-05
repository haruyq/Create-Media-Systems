package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.stream.ProviderUrls;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves a page to its media with yt-dlp on this computer (see Tools), for displays: YouTube with the cookies the
 * browser extension sent (see CookieReceiver), PO Tokens from the provider, and Deno for its challenges; and every
 * other site yt-dlp knows. Only formats with plain URLs are picked, never SABR-only ones (yt-dlp has no URL for
 * those). HLS comes first: VLC plays its master playlist and seeks in it. YouTube's DASH files can't stand in for
 * it: those of some clients (TV) answer a Range header with 403, and VLC 3 crashes seeking a DASH manifest that
 * puts the byte range in each segment's URL instead. Separate MP4 video and audio of other sites play as a local
 * DASH manifest, which VLC can seek in (see NativeMediaResolver).
 */
public final class YtDlp {
    /**
     * HLS, else plain HTTPS MP4 video with M4A audio (local DASH), else a single HTTPS file, else whatever there is;
     * {@code %s} is the height cap. yt-dlp leaves out formats it has no URL for, which SABR-only ones are.
     */
    private static final String FORMAT = "bv*%1$s[protocol^=m3u8]+ba[protocol^=m3u8]/b%1$s[protocol^=m3u8]"
            + "/bv*%1$s[ext=mp4][protocol=https]+ba[ext=m4a][protocol=https]/b%1$s[protocol=https]/bv*%1$s+ba/b%1$s/b";
    private static final long TIMEOUT_SECONDS = 120;

    private YtDlp() {
    }

    /** {@code page}'s media, at most {@code maxHeight} tall (0 for any); fails with yt-dlp's reason. */
    static NativeMediaResolver.Prepared resolve(URI page, int maxHeight) throws IOException, InterruptedException {
        String out = run(page, TIMEOUT_SECONDS, "-J", "-f", String.format(java.util.Locale.ROOT, FORMAT, height(maxHeight)));
        return prepare(JsonParser.parseString(out).getAsJsonObject(), maxHeight);
    }

    /** Whether {@code url} is a YouTube playlist page (not a video in one), which a disc's playlist screen expands. */
    public static boolean isPlaylist(String url) {
        try {
            URI uri = new URI(url.strip());
            return ProviderUrls.youtube(uri) && "/playlist".equals(uri.getPath())
                    && uri.getQuery() != null && uri.getQuery().matches("(.*&)?list=[^&]+.*");
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** The first {@code max} (at least 1) video links of the playlist at {@code page}; fails with yt-dlp's reason. */
    public static List<String> playlist(String page, int max) throws IOException, InterruptedException {
        return run(URI.create(page.strip()), TIMEOUT_SECONDS, "--flat-playlist", "--print", "url",
                "--playlist-items", "1:" + Math.max(1, max)).lines().map(String::strip).filter(l -> !l.isEmpty())
                .toList();
    }

    /**
     * The non-empty {@code rows}, each playlist in {@code videos} replaced by its videos: as many as fit in
     * {@code capacity}, room for the other rows kept.
     */
    public static List<String> fill(List<String> rows, Map<String, List<String>> videos, int capacity) {
        List<String> kept = rows.stream().map(String::strip).filter(row -> !row.isEmpty()).toList();
        int room = capacity - (int) kept.stream().filter(row -> !videos.containsKey(row)).count();
        List<String> out = new ArrayList<>();
        for (String row : kept) {
            List<String> list = videos.get(row);
            if (list == null) {
                out.add(row);
            } else {
                List<String> taken = list.subList(0, Math.clamp(room, 0, list.size()));
                out.addAll(taken);
                room -= taken.size();
            }
        }
        return out;
    }

    /**
     * Runs yt-dlp on {@code page} with {@code options} (and those every run takes: Deno, the cookies, the PO Token
     * provider), returning what it printed; fails with its reason, or if it takes longer than {@code timeoutSeconds}.
     */
    static String run(URI page, long timeoutSeconds, String... options) throws IOException, InterruptedException {
        Tools.Installed tools = Tools.ensure();
        // UTF-8 out, as read below: the frozen yt-dlp.exe ignores PYTHONUTF8 and would print paths (a Japanese game
        // folder) in the system code page.
        List<String> command = new ArrayList<>(List.of(tools.ytDlp().toString(), "--ignore-config", "--no-warnings",
                "--no-playlist", "--encoding", "utf-8", "--js-runtimes", "deno:" + tools.deno()));
        command.addAll(List.of(options));
        if (Files.isRegularFile(CookieReceiver.FILE)) {
            command.addAll(List.of("--cookies", CookieReceiver.FILE.toString()));
        }
        String pot = Tools.potServer(tools);
        if (pot != null) {
            command.addAll(List.of("--plugin-dirs", tools.plugins().toString(),
                    "--extractor-args", "youtubepot-bgutilhttp:base_url=" + pot,
                    // mweb's streams need a PO Token, which the provider gives: another client to fall back on.
                    "--extractor-args", "youtube:player_client=default,mweb"));
        }
        command.add("--");
        command.add(page.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        Process process = builder.start();
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("yt-dlp took too long");
        }
        if (process.exitValue() != 0) {
            throw new IOException(reason(new String(err.join(), StandardCharsets.UTF_8)));
        }
        return new String(out.join(), StandardCharsets.UTF_8);
    }

    /** yt-dlp's format filter for videos at most {@code maxHeight} tall; empty for any (0). */
    static String height(int maxHeight) {
        return maxHeight > 0 ? "[height<=?" + maxHeight + "]" : "";
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** yt-dlp's last error line, which names the problem (bot check, private video) but no signed URL. */
    static String reason(String stderr) {
        String reason = "yt-dlp could not resolve this page";
        for (String line : stderr.split("\\R")) {
            if (line.startsWith("ERROR:")) {
                reason = line.substring(6).trim();
            }
        }
        return reason.length() > 300 ? reason.substring(0, 300) : reason;
    }

    /**
     * What VLC opens for yt-dlp's {@code -J} output (see the class comment); a master playlist plays no variant taller
     * than {@code maxHeight} (0 for any).
     */
    static NativeMediaResolver.Prepared prepare(JsonObject info, int maxHeight) throws IOException, InterruptedException {
        boolean live = info.has("is_live") && !info.get("is_live").isJsonNull() && info.get("is_live").getAsBoolean();
        double duration = live || !info.has("duration") || info.get("duration").isJsonNull() ? 0
                : info.get("duration").getAsDouble();
        List<JsonObject> parts = new ArrayList<>();
        if (info.has("requested_formats") && !info.get("requested_formats").isJsonNull()) {
            info.getAsJsonArray("requested_formats").forEach(part -> parts.add(part.getAsJsonObject()));
        } else {
            parts.add(info);
        }
        JsonObject video = parts.stream().filter(part -> !"none".equals(NativeMedia.text(part, "vcodec", "none")))
                .findFirst().orElse(parts.getFirst());
        JsonObject audio = parts.size() > 1 ? parts.stream().filter(part -> part != video).findFirst().orElseThrow()
                : null;
        if (audio != null && !live && protocol(video).equals("https") && protocol(audio).equals("https")
                && "mp4".equals(NativeMedia.text(video, "ext", "")) && "m4a".equals(NativeMedia.text(audio, "ext", ""))) {
            JsonObject response = new JsonObject();
            response.addProperty("mode", "native");
            response.add("video", track(video, "video"));
            JsonArray audios = new JsonArray();
            audios.add(track(audio, "audio"));
            response.add("audio", audios);
            response.add("duration", info.get("duration"));
            NativeMediaResolver.Prepared dash = NativeMediaResolver.prepare(response, false);
            return new NativeMediaResolver.Prepared(dash.uri(), false, null, duration, dash.options());
        }
        List<JsonObject> tracks = new ArrayList<>(List.of(track(video, "video")));
        if (audio != null) {
            tracks.add(track(audio, "audio"));
        }
        List<String> options = new ArrayList<>(NativeMedia.httpOptions(tracks));
        URI media;
        if (protocol(video).startsWith("m3u8")) {
            // The master playlist has every variant, audio too; VLC picks the best (adaptive-logic, see Prepared)
            // up to the cap, and seeks in it.
            media = URI.create(NativeMedia.text(video, "manifest_url", NativeMedia.text(video, "url", "")));
            if (maxHeight > 0) {
                options.add(":adaptive-maxheight=" + maxHeight);
            }
        } else {
            media = NativeMedia.origin(tracks.getFirst());
            if (audio != null) {
                options.add(":input-slave=" + NativeMedia.origin(tracks.get(1)).toASCIIString());
            }
        }
        return NativeMediaResolver.Prepared.of(media, live, null, duration, options);
    }

    private static String protocol(JsonObject format) {
        return NativeMedia.text(format, "protocol", "");
    }

    /**
     * A track in the API's shape (see API.md's /resolve), which NativeMediaResolver plays; only the origin headers
     * VLC can send (User-Agent, Referer) are kept.
     */
    private static JsonObject track(JsonObject format, String kind) {
        JsonObject track = new JsonObject();
        track.addProperty("kind", kind);
        track.addProperty("url", NativeMedia.text(format, "url", ""));
        JsonObject headers = new JsonObject();
        JsonObject given = format.has("http_headers") && format.get("http_headers").isJsonObject()
                ? format.getAsJsonObject("http_headers") : new JsonObject();
        for (var header : given.entrySet()) {
            if (NativeMedia.forwarded(header.getKey())) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        track.add("headers", headers);
        track.addProperty("mime", kind.equals("audio") ? "audio/mp4" : "video/mp4");
        copy(format, kind.equals("audio") ? "acodec" : "vcodec", track, "codec");
        copy(format, "width", track, "width");
        copy(format, "height", track, "height");
        copy(format, "language", track, "language");
        @Nullable JsonElement tbr = format.get("tbr");
        if (tbr != null && !tbr.isJsonNull()) {
            track.addProperty("bandwidth", Math.round(tbr.getAsDouble() * 1000));
        }
        return track;
    }

    private static void copy(JsonObject from, String name, JsonObject to, String as) {
        JsonElement value = from.get(name);
        if (value != null && !value.isJsonNull()) {
            to.add(as, value);
        }
    }
}
