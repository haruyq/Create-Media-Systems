package org.haruyq.createmediasystems.p2p;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.haruyq.createmediasystems.ApiHttp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Direct P2P's protocol with the API (see P2p): its addresses, and the ticket it gives each peer. Free of Minecraft
 * classes, as Transport is, so the standalone test app (tools/p2p-test) builds them as they are.
 */
public final class Protocol {
    public static final int VERSION = 1;
    /** Most a connection is taken to carry, in bit/s: 100 Gbps. */
    public static final long MAX_BPS = 100_000_000_000L;
    /** Longest session id, token, list of STUN servers and STUN URL a ticket holds (see Ticket). */
    public static final int MAX_SESSION_ID = 128, MAX_TOKEN = 512, MAX_STUN_URLS = 16, MAX_STUN_URL = 256;
    public static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{8," + MAX_SESSION_ID + "}");
    /** A secret the API takes: a ticket's token, and the server's own (see P2p). */
    public static final Pattern TOKEN = Pattern.compile("[\\x21-\\x7e]{16," + MAX_TOKEN + "}");

    private Protocol() {
    }

    /**
     * The API endpoint {@code path} under {@code apiBase}, keeping any prefix it has, as {@code ws(s)} if
     * {@code websocket}. Only HTTPS, or HTTP to this computer, carries secrets and tickets.
     */
    public static URI endpoint(String apiBase, String path, boolean websocket) {
        URI base = URI.create(apiBase.trim());
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !(scheme.equals("https") || scheme.equals("http") && loopback(base.getHost()))) {
            throw new IllegalArgumentException("P2P needs an https:// API URL (or http:// to this computer)");
        }
        String url = ApiHttp.api(base.toString(), path);
        return URI.create(websocket ? (scheme.equals("https") ? "wss" : "ws") + url.substring(scheme.length()) : url);
    }

    private static boolean loopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.matches("127(\\.\\d{1,3}){3}") || host.equals("[::1]");
    }

    /** A peer's own ticket to the API's signaling (see README). */
    public record Ticket(String sessionId, UUID peerId, UUID hostId, boolean host, String token, List<String> stunUrls) {
        public Ticket {
            if (!SESSION_ID.matcher(sessionId).matches() || !TOKEN.matcher(token).matches() || stunUrls.size() > MAX_STUN_URLS
                    || stunUrls.stream().anyMatch(u -> u.length() > MAX_STUN_URL || !u.toLowerCase(Locale.ROOT).startsWith("stun:"))) {
                throw new IllegalArgumentException("Not a valid P2P ticket");
            }
            stunUrls = List.copyOf(stunUrls);
        }

        /** The API's ticket JSON; fails on anything else, a TURN server included. */
        public static Ticket parse(String json) {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.get("protocol").getAsInt() != VERSION) {
                throw new IllegalArgumentException("Unknown P2P protocol");
            }
            String role = o.get("role").getAsString();
            if (!role.equals("host") && !role.equals("viewer")) {
                throw new IllegalArgumentException("Unknown P2P role");
            }
            List<String> stun = new ArrayList<>();
            JsonArray urls = o.getAsJsonArray("stun_urls");
            for (JsonElement url : urls) {
                stun.add(url.getAsString());
            }
            return new Ticket(o.get("session_id").getAsString(), UUID.fromString(o.get("peer_id").getAsString()),
                    UUID.fromString(o.get("host_id").getAsString()), role.equals("host"), o.get("token").getAsString(), stun);
        }

        @Override
        public String toString() {
            return "Ticket[" + (host ? "host" : "viewer") + "]";
        }
    }
}
