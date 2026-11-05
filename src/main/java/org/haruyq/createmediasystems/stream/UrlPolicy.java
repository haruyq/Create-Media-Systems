package org.haruyq.createmediasystems.stream;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonParser;

/** Which links the server allows, and the API's addresses and errors; kept free of Minecraft classes so they can be unit tested. */
public final class UrlPolicy {
    /** Longest link a disc, image viewer or announcement takes. */
    public static final int MAX_LENGTH = 2048;

    private UrlPolicy() {
    }

    /** HTTP(S)/RTSP URL whose host is explicitly allowed by the server. */
    public static boolean isAllowed(String url, List<? extends String> allowedHosts) {
        URI uri = parse(url);
        if (uri == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return allowedHosts.stream().map(a -> a.toLowerCase(Locale.ROOT))
                .anyMatch(a -> host.equals(a) || host.endsWith("." + a));
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

    /** RTSP stays on the client; the HTTP API never receives credentials or media. */
    public static boolean isRtsp(URI uri) {
        return uri != null && uri.getHost() != null && "rtsp".equalsIgnoreCase(uri.getScheme())
                && !uri.isOpaque() && uri.getFragment() == null && uri.getPort() != 0
                && uri.getPort() <= 65535;
    }

    /** {@code {apiBase}/{path}}, however many slashes the configured base ends with. */
    public static String api(String apiBase, String path) {
        return apiBase.replaceAll("/+$", "") + "/" + path;
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

    /** {@code GET {apiBase}/{name}?url=<source>} (video, info, resolve; see API.md). */
    public static String endpoint(String apiBase, String name, String url) {
        return api(apiBase, name) + "?url=" + URLEncoder.encode(url.trim(), StandardCharsets.UTF_8);
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https") || isRtsp(uri))) {
                return null;
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

}
