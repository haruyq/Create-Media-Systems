package org.haruyq.createmediasystems.stream;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.ServerConfig;

import org.jetbrains.annotations.Nullable;

/** Which links the server allows; the host check is kept free of Minecraft classes so it can be unit tested. */
public final class UrlPolicy {
    /** Longest link a disc, image viewer or announcement takes. */
    public static final int MAX_LENGTH = 2048;

    private UrlPolicy() {
    }

    /** Every stream a server hands to clients passes this check, whoever wrote the URL (see ServerConfig#ALLOWED_HOSTS). */
    public static boolean isAllowed(@Nullable String url) {
        return isAllowed(url, ServerConfig.ALLOWED_HOSTS.get());
    }

    /** Whether {@code url} is a kind CMS plays (see MediaKind) and, where that kind asks, its host is on {@code allowedHosts}. */
    public static boolean isAllowed(@Nullable String url, List<? extends String> allowedHosts) {
        MediaKind kind = MediaKind.of(url);
        if (kind == null || !kind.listedHost) {
            return kind != null;
        }
        URI uri = MediaKind.uri(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return allowedHosts.stream().map(a -> a.toLowerCase(Locale.ROOT))
                .anyMatch(a -> host.equals(a) || host.endsWith("." + a));
    }
}
