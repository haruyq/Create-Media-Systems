package org.haruyq.createmediasystems.playback;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.ServerConfig;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The pages the API's extension plays (GET /extension/patterns, see API.md). They play through the API's relay
 * (/video, /stream) instead of on this computer; the mod itself knows no such site.
 */
final class ExtensionUrls {
    private static final long REFRESH_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final AtomicBoolean FETCHING = new AtomicBoolean();

    private record Known(String api, List<Pattern> urls, long at) {
    }

    private static volatile Known known = new Known("", List.of(), 0);

    private ExtensionUrls() {
    }

    static boolean matches(URI uri) {
        refresh();
        return matches(known.urls(), uri);
    }

    /** Whether {@code uri} is one of the pages {@code urls} (see use) names. */
    static boolean matches(List<Pattern> urls, URI uri) {
        String url = uri.toString();
        return urls.stream().anyMatch(p -> p.matcher(url).matches());
    }

    /** Fetches the patterns again in the background if the API changed or they are older than REFRESH_NANOS. */
    static void refresh() {
        String base;
        try {
            base = ServerConfig.API_URL.get();
        } catch (RuntimeException configNotLoaded) {
            return;
        }
        Known now = known;
        if ((now.api().equals(base) && System.nanoTime() - now.at() < REFRESH_NANOS) || !FETCHING.compareAndSet(false, true)) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(ApiHttp.api(base, "extension/patterns")))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").build();
        NativeMediaResolver.HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((reply, error) -> {
            // An API without an extension (or too old to have this endpoint) plays nothing through one. One that
            // cannot be reached keeps what it said last, till the next try.
            List<Pattern> urls = now.api().equals(base) ? now.urls() : List.of();
            if (error == null) {
                urls = reply.statusCode() == 200 ? use(reply.body()) : List.of();
            }
            known = new Known(base, urls, System.nanoTime());
            FETCHING.set(false);
        });
    }

    /** The {@code urls} patterns of a GET /extension/patterns answer; ones Java cannot read are left out. */
    static List<Pattern> use(String body) {
        List<Pattern> urls = new ArrayList<>();
        try {
            JsonElement list = JsonParser.parseString(body).getAsJsonObject().get("urls");
            if (list != null && list.isJsonArray()) {
                for (JsonElement pattern : list.getAsJsonArray()) {
                    try {
                        urls.add(Pattern.compile(pattern.getAsString()));
                    } catch (PatternSyntaxException | UnsupportedOperationException | IllegalStateException ignored) {
                    }
                }
            }
        } catch (RuntimeException invalid) {
            return List.of();
        }
        return List.copyOf(urls);
    }
}
