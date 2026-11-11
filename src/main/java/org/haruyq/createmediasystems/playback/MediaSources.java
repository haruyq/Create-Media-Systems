package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.ProviderUrls;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import net.minecraft.Util;

/**
 * What a player opens for a source URL, by its kind (see MediaKind): a web page resolved to its origin media by this
 * client (see NativeMediaResolver), one the API's extension plays through the API's relay, a media file as it is;
 * an RTSP stream as it is, over TCP with little buffering; and any other kind through the opener its feature gives
 * (a shared disc's, see DiscClient).
 */
public final class MediaSources {
    /** How long before a video ends the next is resolved (see resolveAhead): well inside a resolution's lifetime. */
    static final double RESOLVE_AHEAD_SECONDS = NativeMediaResolver.CACHE_NANOS / 3 / 1e9;
    /** What was resolved ahead (URL and height) and when (System.nanoTime), dropped as the resolution expires. */
    private static final Map<String, Long> RESOLVED_AHEAD = new ConcurrentHashMap<>();
    /** How the kinds this package doesn't open itself are opened; set as the client starts. */
    private static final Map<MediaKind, Opener> OPENERS = new EnumMap<>(MediaKind.class);

    private MediaSources() {
    }

    /** How a feature's kind of link opens on this client: what the player plays for it from {@code startSeconds} in. */
    public interface Opener {
        /** Fails with a reason safe to show the player. */
        Opened open(URI source, double startSeconds) throws IOException;
    }

    /** What an Opener gives the player: the media, its length in seconds (0 if VLC is to tell) and VLC options for it. */
    public record Opened(URI media, double duration, List<String> options) {
    }

    /** {@code kind}'s links open through {@code opener} (as the client starts). */
    public static synchronized void opens(MediaKind kind, Opener opener) {
        OPENERS.put(kind, opener);
    }

    /** Whether {@code url} is one the server allows that goes through the resolver (and so may be kept). */
    private static boolean resolvable(String url, URI uri) {
        return MediaKind.of(url) == MediaKind.WEB && UrlPolicy.isAllowed(url) && asIs(uri) == null;
    }

    /** What a player opens for the web link {@code uri} without the resolver (an extension's page, a media file); else null. */
    @Nullable
    private static NativeMediaResolver.Prepared asIs(URI uri) {
        if (ExtensionUrls.matches(uri)) {
            // The extension's relay is one continuous shared HLS stream, even for an on-demand program. Each opening
            // gets its own /video session, which the API ties a live player to.
            return NativeMediaResolver.Prepared.relay(URI.create(ApiHttp.endpoint(ServerConfig.API_URL.get(), "video",
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
     * What a player opens for {@code uri}, {@code startSeconds} into it. Without {@code video} (a record), a
     * resolvable page plays as its kept audio if this computer has it (see AudioCache), else resolved now for its
     * audio alone, which then starts to be kept. Fails with a reason safe to show (it leaves out the URLs).
     */
    static NativeMediaResolver.Prepared resolve(URI uri, boolean video, int maxHeight, double startSeconds)
            throws IOException {
        String url = uri.toString();
        MediaKind kind = MediaKind.of(url);
        if (kind == null || !UrlPolicy.isAllowed(url)) {
            throw new IOException("This playback URL is not allowed by the server");
        }
        return switch (kind) {
            case WEB -> web(uri, video, maxHeight);
            case RTSP -> NativeMediaResolver.Prepared.rtsp(uri);
            default -> {
                Opener opener;
                synchronized (MediaSources.class) {
                    opener = OPENERS.get(kind);
                }
                if (opener == null) {
                    throw new IOException("This client can't play this kind of link");
                }
                Opened opened = opener.open(uri, startSeconds);
                yield NativeMediaResolver.Prepared.of(opened.media(), false, null, opened.duration(), opened.options());
            }
        };
    }

    private static NativeMediaResolver.Prepared web(URI uri, boolean video, int maxHeight) throws IOException {
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

    /** How much of the copy of {@code url} kept here for {@code video} is downloaded, 0..1; -1 if none is kept or coming. */
    static double downloaded(String url, Streams.Video video) {
        try {
            URI uri = URI.create(url);
            return resolvable(url, uri) ? VideoCache.get().progress(uri, height(video)) : -1;
        } catch (IllegalArgumentException invalid) {
            return -1;
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
