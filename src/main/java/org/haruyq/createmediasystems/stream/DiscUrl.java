package org.haruyq.createmediasystems.stream;

import java.net.URI;
import java.util.UUID;

/**
 * The link of a disc a player shares from their own drive or a disc image ({@code cms-disc://<owner>/<source>}, see
 * MediaKind#DISC): who hosts it, and which of their reads it is.
 */
public record DiscUrl(UUID owner, UUID source) {
    public static DiscUrl parse(String value) {
        try {
            URI uri = URI.create(value);
            if (!"cms-disc".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            DiscUrl disc = new DiscUrl(UUID.fromString(uri.getHost()), UUID.fromString(uri.getPath().substring(1)));
            return disc.toString().equals(value) ? disc : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "cms-disc://" + owner + "/" + source;
    }
}
