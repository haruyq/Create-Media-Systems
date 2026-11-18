package org.haruyq.createmediasystems.stream;

import java.net.URI;
import java.util.UUID;

/**
 * The link of a disc a player shares from their own drive or a disc image ({@code cms-disc://<owner>/<source>}, see
 * MediaKind#DISC), or of their screen ({@code cms-screen://<owner>/<source>}, see MediaKind#SCREEN): who hosts it,
 * and which of their shares it is.
 */
public record DiscUrl(UUID owner, UUID source, boolean screen) {
    public DiscUrl(UUID owner, UUID source) {
        this(owner, source, false);
    }

    /** A disc's or a screen's link; null for anything else. */
    public static DiscUrl parse(String value) {
        try {
            URI uri = URI.create(value);
            boolean screen = "cms-screen".equals(uri.getScheme());
            if (!screen && !"cms-disc".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            DiscUrl disc = new DiscUrl(UUID.fromString(uri.getHost()), UUID.fromString(uri.getPath().substring(1)), screen);
            return disc.toString().equals(value) ? disc : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Override
    public String toString() {
        return (screen ? "cms-screen://" : "cms-disc://") + owner + "/" + source;
    }
}
