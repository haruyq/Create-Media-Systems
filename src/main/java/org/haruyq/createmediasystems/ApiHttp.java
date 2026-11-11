package org.haruyq.createmediasystems;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.google.gson.JsonParser;

/**
 * The CMS-API's addresses (see API.md) and how its errors read. Free of Minecraft classes, so it is unit tested and
 * the standalone P2P app (tools/p2p-test) builds it as it is.
 */
public final class ApiHttp {
    private ApiHttp() {
    }

    /** Whether {@code base} may be the API's base URL (see ServerConfig#API_URL): an http(s) URL with a host. */
    public static boolean isBase(String base) {
        try {
            URI uri = new URI(base);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception notALink) {
            return false;
        }
    }

    /** {@code {apiBase}/{path}}, however many slashes the configured base ends with. */
    public static String api(String apiBase, String path) {
        return apiBase.replaceAll("/+$", "") + "/" + path;
    }

    /** {@code GET {apiBase}/{name}?url=<source>} (video, info, resolve; see API.md). */
    public static String endpoint(String apiBase, String name, String url) {
        return api(apiBase, name) + "?url=" + URLEncoder.encode(url.trim(), StandardCharsets.UTF_8);
    }

    /**
     * {@code GET {apiBase}/stream?url=<source>[&start=<seconds>][&channel=left|right]&format=ogg}, see API.md. Ogg
     * Vorbis is a tenth of the raw PCM's bandwidth; an API without it answers PCM, which plays as before.
     */
    public static String streamUrl(String apiBase, String url, double startSeconds, String channel) {
        String query = startSeconds > 0 ? "&start=" + String.format(Locale.ROOT, "%.2f", startSeconds) : "";
        return endpoint(apiBase, "stream", url) + query + (channel != null ? "&channel=" + channel : "")
                + "&format=ogg";
    }

    /**
     * A readable cause for {@code error}; never null. Uses the innermost cause, since wrappers say little: async
     * code wraps in CompletionException, and the JDK HTTP client reports any failed body as just "closed".
     */
    public static String reason(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** The API's error reason: the {@code detail} of its JSON error body, else the status code. */
    public static String errorDetail(int status, String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject().get("detail").getAsString();
        } catch (RuntimeException e) {
            return "HTTP " + status;
        }
    }
}
