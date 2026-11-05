package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.ProviderUrls;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import net.minecraft.Util;

/**
 * What a player opens for a source URL: supported pages resolved to their origin media by this client (see
 * NativeMediaResolver), pages the API's extension plays through the API's relay, and anything else as it is.
 */
final class MediaSources {
    /** How long before a video ends the next is resolved (see resolveAhead): well inside a resolution's lifetime. */
    static final double RESOLVE_AHEAD_SECONDS = NativeMediaResolver.CACHE_NANOS / 3 / 1e9;
    /** What was resolved ahead (URL and height) and when (System.nanoTime), dropped as the resolution expires. */
    private static final Map<String, Long> RESOLVED_AHEAD = new ConcurrentHashMap<>();

    private MediaSources() {
    }

    /** Whether {@code url} is one the server allows that goes through the resolver (and so may be kept). */
    private static boolean resolvable(String url, URI uri) {
        return ServerConfig.isAllowed(url) && asIs(uri) == null;
    }

    /** What a player opens for {@code uri} without the resolver (RTSP, an extension's page, a media file); else null. */
    @Nullable
    private static NativeMediaResolver.Prepared asIs(URI uri) {
        if (UrlPolicy.isRtsp(uri)) {
            return new NativeMediaResolver.Prepared(uri, true);
        }
        if (ExtensionUrls.matches(uri)) {
            // The extension's relay is one continuous shared HLS stream, even for an on-demand program. Each opening
            // gets its own /video session, which the API ties a live player to.
            return NativeMediaResolver.Prepared.relay(URI.create(UrlPolicy.endpoint(ServerConfig.API_URL.get(), "video",
                    uri.toString()) + "&session=" + UUID.randomUUID()));
        }
        return directMedia(uri) ? new NativeMediaResolver.Prepared(uri, false) : null;
    }

    /** The tallest picture this computer plays {@code video} at: its disc's, or its own limit if lower. */
    static int height(Streams.Video video) {
        return ClientConfig.maxHeight(video.maxHeight());
    }

    private static boolean directMedia(URI uri) {
        return uri.getHost() != null && uri.getScheme() != null
                && Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                && uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT)
                .matches(".*\\.(?:m3u8|mpd|mp4|m4v|mkv|webm|mov|avi|ts|mpeg|mpg|mp3|m4a|aac|ogg|opus|flac|wav)");
    }

    /**
     * What a player opens for {@code uri}. Without {@code video} (a record), a resolvable page plays as its kept
     * audio if this computer has it (see AudioCache), else resolved now for its audio alone, which then starts to
     * be kept. Fails with a reason safe to show (it leaves out the URLs).
     */
    static NativeMediaResolver.Prepared resolve(URI uri, boolean video, int maxHeight) throws IOException {
        if (!ServerConfig.isAllowed(uri.toString())) {
            throw new IOException("This playback URL is not allowed by the server");
        }
        NativeMediaResolver.Prepared asIs = asIs(uri);
        if (asIs != null) {
            return asIs;
        }
        if (video) {
            NativeMediaResolver.Prepared kept = VideoCache.get().lookup(uri, maxHeight);
            if (kept != null) {
                return kept;
            }
            // Any page yt-dlp knows (see NativeMediaResolver#fetch); one neither it nor the API knows, VLC tries as is.
            try {
                NativeMediaResolver.Prepared media = NativeMediaResolver.resolve(uri, false, maxHeight);
                if (!media.live()) {
                    VideoCache.get().fill(uri, maxHeight);
                }
                return media;
            } catch (IllegalStateException failed) {
                if (!ProviderUrls.matches(uri)) {
                    return new NativeMediaResolver.Prepared(uri, false);
                }
                throw new IOException(failed.getMessage());
            }
        }
        if (!ProviderUrls.matches(uri)) {
            return new NativeMediaResolver.Prepared(uri, false);
        }
        try {
            URI kept = AudioCache.get().lookup(uri);
            if (kept != null) {
                return new NativeMediaResolver.Prepared(kept, false);
            }
            NativeMediaResolver.Prepared media = NativeMediaResolver.resolve(uri, true, 0);
            if (media.download() != null) {
                AudioCache.get().fill(uri, media.uri(), media.download());
            }
            return media;
        } catch (IllegalStateException failed) {
            throw new IOException(failed.getMessage()); // the resolver's messages leave out the URLs
        }
    }

    /** {@code uri} would not play: what it resolved to, or the copy kept of it, may be stale. */
    static void forget(URI uri) {
        NativeMediaResolver.forget(uri);
        AudioCache.get().forget(uri);
        VideoCache.get().forget(uri);
    }

    /**
     * Resolves the video at {@code url} (a disc's next, about to play) in the background, as a disc that plays
     * its disc plays it, unless it is kept already: playing it then finds it resolved (see
     * NativeMediaResolver#resolve). Once per resolution's lifetime; for a page yt-dlp may fetch and the server allows.
     */
    static void resolveAhead(String url, Streams.Video video) {
        int height = height(video);
        long now = System.nanoTime();
        RESOLVED_AHEAD.values().removeIf(at -> now - at > NativeMediaResolver.CACHE_NANOS);
        try {
            URI uri = URI.create(url);
            if (!resolvable(url, uri) || VideoCache.get().has(uri, height)
                    || RESOLVED_AHEAD.putIfAbsent(url + " " + height, now) != null) {
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    NativeMediaResolver.resolve(uri, false, height);
                } catch (RuntimeException failed) {
                    // resolved again as it plays, which says why it can't
                }
            }, Util.nonCriticalIoPool());
        } catch (IllegalArgumentException invalid) {
        }
    }

    /**
     * Starts keeping the video at {@code url} (a disc's next) as its disc plays it, if
     * it is a page yt-dlp may fetch and the server allows.
     */
    static void fetchAhead(String url, Streams.Video video) {
        try {
            URI uri = URI.create(url);
            if (resolvable(url, uri)) {
                VideoCache.get().fill(uri, height(video));
            }
        } catch (IllegalArgumentException invalid) {
        }
    }
}
