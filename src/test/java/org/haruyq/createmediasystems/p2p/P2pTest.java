package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

class P2pTest {
    static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    static String ticket(String role, UUID peer, String stun) {
        return "{\"protocol\":1,\"session_id\":\"abcdefgh12345678\",\"peer_id\":\"" + peer + "\",\"host_id\":\"" + HOST
                + "\",\"role\":\"" + role + "\",\"token\":\"tok_0123456789abcdefghij\",\"stun_urls\":[" + stun + "]}";
    }

    @Test
    void parsesTicketsAndRefusesTurnOrUnknownOnes() {
        Protocol.Ticket ticket = Protocol.Ticket.parse(ticket("viewer", VIEWER, "\"stun:stun.example.com:3478\""));
        assertFalse(ticket.host());
        assertEquals(VIEWER, ticket.peerId());
        assertEquals(List.of("stun:stun.example.com:3478"), ticket.stunUrls());
        assertFalse(ticket.toString().contains("tok_"), "a ticket's token stays out of logs");
        assertThrows(IllegalArgumentException.class, () -> Protocol.Ticket.parse(ticket("viewer", VIEWER, "\"turn:relay.example.com\"")));
        assertThrows(IllegalArgumentException.class, () -> Protocol.Ticket.parse(ticket("admin", VIEWER, "")));
        assertThrows(IllegalArgumentException.class, () -> Protocol.Ticket.parse(ticket("host", HOST, "").replace("\"protocol\":1", "\"protocol\":2")));
        assertThrows(IllegalArgumentException.class, () -> Protocol.Ticket.parse(ticket("host", HOST, "").replace("abcdefgh12345678", "../../x")));
    }

    @Test
    void sendsATicketToItsPlayerAsBefore() {
        P2p.Ticket sent = new P2p.Ticket(Protocol.Ticket.parse(ticket("viewer", VIEWER, "\"stun:stun.example.com:3478\"")), 7);
        ByteBuf buffer = Unpooled.buffer();
        P2p.Ticket.CODEC.encode(buffer, sent);
        // The fields in the order they always went: session, peer, host, role, token, STUN, request.
        FriendlyByteBuf expected = new FriendlyByteBuf(Unpooled.buffer());
        expected.writeUtf("abcdefgh12345678").writeUUID(VIEWER).writeUUID(HOST).writeBoolean(false)
                .writeUtf("tok_0123456789abcdefghij").writeVarInt(1).writeUtf("stun:stun.example.com:3478").writeVarInt(7);
        assertArrayEquals(ByteBufUtil.getBytes(expected), ByteBufUtil.getBytes(buffer));
        assertEquals(sent, P2p.Ticket.CODEC.decode(buffer));
    }

    @Test
    void keepsTheApiPrefixAndRefusesPlainHttpElsewhere() {
        assertEquals("wss://example.com/cms/api/p2p/sessions/abc/signal",
                Protocol.endpoint("https://example.com/cms/api/", "p2p/sessions/abc/signal", true).toString());
        assertEquals("ws://127.0.0.1:8000/p2p/sessions", Protocol.endpoint("http://127.0.0.1:8000", "p2p/sessions", true).toString());
        assertEquals("http://localhost:8000/x/p2p/sessions", Protocol.endpoint("http://localhost:8000/x", "p2p/sessions", false).toString());
        assertThrows(IllegalArgumentException.class, () -> Protocol.endpoint("http://192.168.1.2:8000", "p2p/sessions", false));
        assertThrows(IllegalArgumentException.class, () -> Protocol.endpoint("https://user:pw@example.com", "p2p/sessions", false));
        assertThrows(IllegalArgumentException.class, () -> Protocol.endpoint("https://example.com/?a=b", "p2p/sessions", false));
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
        assertTrue(Protocol.validBps(1));
        assertTrue(Protocol.validBps(Protocol.MAX_BPS));
        assertFalse(Protocol.validBps(0));
        assertFalse(Protocol.validBps(-8));
        assertFalse(Protocol.validBps(Protocol.MAX_BPS + 1));
    }
}
