package org.haruyq.createmediasystems.stream;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * What kind of link a track is, told from its URL ({@link #of}): each kind says whether the server's host list
 * governs it, how long its listeners are waited for, and what it is without asking the API. How a client opens
 * each kind is playback's (see MediaSources).
 */
public enum MediaKind {
    /** An http(s) page or media file: the API, a provider or the file's host plays it. */
    WEB(true, Streams.BARRIER_TICKS, null),
    /** A live RTSP camera or encoder, which clients open themselves; the API never sees it or its credentials. */
    RTSP(true, Streams.BARRIER_TICKS, "RTSP live"),
    /**
     * A disc a player shares over P2P (see DiscUrl): allowed whatever the host list says, as it never leaves the
     * players, and waited for as long as a track may load, since joining its host and its first segment take a while.
     */
    DISC(false, Streams.MAX_LOAD_TICKS, "DVD / Blu-ray");

    /** Whether only a host on the server's list (or a subdomain of one) may be played (see UrlPolicy). */
    final boolean listedHost;
    /** How long a track of this kind waits for the rest of its listeners once one has it (see Streams#loading). */
    public final long barrierTicks;
    /** What /info says it is without asking the API (no length: it has none, or a listener tells it); null to ask. */
    @Nullable
    final String title;

    MediaKind(boolean listedHost, long barrierTicks, @Nullable String title) {
        this.listedHost = listedHost;
        this.barrierTicks = barrierTicks;
        this.title = title;
    }

    /** {@code url}'s kind; null for anything CMS doesn't play (another scheme, no host, not a link). */
    @Nullable
    public static MediaKind of(@Nullable String url) {
        if (url == null) {
            return null;
        }
        if (DiscUrl.parse(url) != null) {
            return DISC;
        }
        URI uri = uri(url);
        if (uri == null || uri.getHost() == null || uri.getScheme() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("rtsp")) {
            return !uri.isOpaque() && uri.getFragment() == null && uri.getPort() != 0 && uri.getPort() <= 65535
                    ? RTSP : null;
        }
        return scheme.equals("http") || scheme.equals("https") ? WEB : null;
    }

    public static long barrierTicks(@Nullable String url) {
        MediaKind kind = of(url);
        return (kind == null ? WEB : kind).barrierTicks;
    }

    /** Whether {@code player} may put {@code url} on a disc or a drive: a shared disc only its host may; any other, anyone. */
    public static boolean writableBy(String url, UUID player) {
        DiscUrl disc = DiscUrl.parse(url);
        return disc == null || disc.owner().equals(player);
    }

    @Nullable
    static URI uri(String url) {
        try {
            return new URI(url.trim());
        } catch (Exception notALink) {
            return null;
        }
    }
}
