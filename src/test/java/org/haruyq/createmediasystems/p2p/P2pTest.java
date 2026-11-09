package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class P2pTest {
    static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    static String ticket(String role, UUID peer, String stun) {
        return "{\"protocol\":1,\"session_id\":\"abcdefgh12345678\",\"peer_id\":\"" + peer + "\",\"host_id\":\"" + HOST
                + "\",\"role\":\"" + role + "\",\"token\":\"tok_0123456789abcdefghij\",\"stun_urls\":[" + stun + "]}";
    }

    @Test
    void parsesTicketsAndRefusesTurnOrUnknownOnes() {
        P2p.Ticket ticket = P2p.Ticket.parse(ticket("viewer", VIEWER, "\"stun:stun.example.com:3478\""));
        assertFalse(ticket.host());
        assertEquals(VIEWER, ticket.peerId());
        assertEquals(List.of("stun:stun.example.com:3478"), ticket.stunUrls());
        assertFalse(ticket.toString().contains("tok_"), "a ticket's token stays out of logs");
        assertThrows(IllegalArgumentException.class, () -> P2p.Ticket.parse(ticket("viewer", VIEWER, "\"turn:relay.example.com\"")));
        assertThrows(IllegalArgumentException.class, () -> P2p.Ticket.parse(ticket("admin", VIEWER, "")));
        assertThrows(IllegalArgumentException.class, () -> P2p.Ticket.parse(ticket("host", HOST, "").replace("\"protocol\":1", "\"protocol\":2")));
        assertThrows(IllegalArgumentException.class, () -> P2p.Ticket.parse(ticket("host", HOST, "").replace("abcdefgh12345678", "../../x")));
    }

    @Test
    void keepsTheApiPrefixAndRefusesPlainHttpElsewhere() {
        assertEquals("wss://example.com/cms/api/p2p/sessions/abc/signal",
                P2p.endpoint("https://example.com/cms/api/", "p2p/sessions/abc/signal", true).toString());
        assertEquals("ws://127.0.0.1:8000/p2p/sessions", P2p.endpoint("http://127.0.0.1:8000", "p2p/sessions", true).toString());
        assertEquals("http://localhost:8000/x/p2p/sessions", P2p.endpoint("http://localhost:8000/x", "p2p/sessions", false).toString());
        assertThrows(IllegalArgumentException.class, () -> P2p.endpoint("http://192.168.1.2:8000", "p2p/sessions", false));
        assertThrows(IllegalArgumentException.class, () -> P2p.endpoint("https://user:pw@example.com", "p2p/sessions", false));
        assertThrows(IllegalArgumentException.class, () -> P2p.endpoint("https://example.com/?a=b", "p2p/sessions", false));
    }

    @Test
    void acceptsDirectCandidatesOnly() {
        assertTrue(Transport.direct("candidate:1 1 udp 2122260223 192.168.1.2 54321 typ host generation 0"));
        assertTrue(Transport.direct("candidate:2 1 udp 1686052607 203.0.113.5 54321 typ srflx raddr 192.168.1.2 rport 54321"));
        assertFalse(Transport.direct("candidate:3 1 udp 41885439 198.51.100.7 3478 typ relay raddr 203.0.113.5 rport 54321"));
        assertFalse(Transport.direct("candidate:4 1 udp 1 1.2.3.4 1"));
    }

    @Test
    void boundsMeasurements() {
        assertTrue(P2p.validBps(1));
        assertTrue(P2p.validBps(P2p.MAX_BPS));
        assertFalse(P2p.validBps(0));
        assertFalse(P2p.validBps(-8));
        assertFalse(P2p.validBps(P2p.MAX_BPS + 1));
    }
}
