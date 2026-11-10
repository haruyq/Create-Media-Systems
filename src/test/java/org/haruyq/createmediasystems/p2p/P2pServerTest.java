package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The server's side of hosting, joining and leaving, with the API's answers held back to come when each test says. */
class P2pServerTest {
    static final UUID HOST = P2pTest.HOST;
    static final UUID VIEWER = P2pTest.VIEWER;

    record Call(String method, String path, CompletableFuture<P2p.Answer> answer) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<CustomPacketPayload> host = new ArrayList<>();
    final List<CustomPacketPayload> viewer = new ArrayList<>();

    @BeforeEach
    void api() {
        P2p.api = (method, path, body) -> {
            CompletableFuture<P2p.Answer> answer = new CompletableFuture<>();
            calls.add(new Call(method, path, answer));
            return answer;
        };
    }

    @AfterEach
    void reset() {
        while (!pending().isEmpty()) {
            calls.forEach(c -> c.answer().complete(new P2p.Answer(204, "")));
        }
        P2p.api = (method, path, body) -> CompletableFuture.completedFuture(new P2p.Answer(204, ""));
        P2p.stop();
        P2p.start();
    }

    @Test
    void hostingAgainIgnoresTheOldSessionsLeave() {
        host(1, "session_one_0000");
        P2p.request(player(HOST, host), new P2p.Request(P2p.Action.HOST, 2, ""), null);
        assertEquals(List.of("DELETE p2p/sessions/session_one_0000"), pending(), "the new room waits for the old one's DELETE");
        // The API closed the old session for that DELETE; its client says so, with the old request's number.
        P2p.leave(HOST, 1);
        answer(1, 204, "");
        answer(2, 200, ticket("host", HOST, "session_two_0000"));
        assertEquals(List.of(), pending(), "the new room is kept");
        assertEquals(2, ((P2p.Ticket) host.getLast()).request());
    }

    @Test
    void rejoiningTheSameRoomWaitsForTheOldSeatsDelete() {
        host(1, "session_one_0000");
        join(1);
        answer(1, 200, ticket("viewer", VIEWER, "session_one_0000"));
        join(2);
        assertEquals(List.of("DELETE p2p/sessions/session_one_0000/peers/" + VIEWER), pending());
        answer(2, 204, "");
        assertEquals(List.of("POST p2p/sessions/session_one_0000/peers"), pending());
        answer(3, 200, ticket("viewer", VIEWER, "session_one_0000"));
        assertEquals(List.of(), pending());
        assertEquals(2, ((P2p.Ticket) viewer.getLast()).request());
        List<CustomPacketPayload> other = new ArrayList<>();
        P2p.request(player(UUID.randomUUID(), other), new P2p.Request(P2p.Action.JOIN, 1, "Viewer"), VIEWER);
        assertInstanceOf(P2p.Notice.class, other.getFirst(), "a viewer hosts no room");
    }

    @Test
    void aTicketForALeftRequestIsRevokedNotSent() {
        host(1, "session_one_0000");
        join(1);
        P2p.leave(VIEWER, 1);
        answer(1, 200, ticket("viewer", VIEWER, "session_one_0000"));
        assertEquals(List.of("DELETE p2p/sessions/session_one_0000/peers/" + VIEWER), pending());
        assertEquals(List.of(), viewer);
        join(2);
        assertEquals(1, pending().size(), "the next join waits for that DELETE");
        answer(2, 204, "");
        assertEquals(List.of("POST p2p/sessions/session_one_0000/peers"), pending());
    }

    @Test
    void aJoinWhoseHostLeftIsToldAndRevoked() {
        UUID third = new UUID(0, 3);
        List<CustomPacketPayload> other = new ArrayList<>();
        host(1, "session_one_0000");
        join(1);
        P2p.request(player(third, other), new P2p.Request(P2p.Action.JOIN, 1, "Host"), HOST);
        P2p.leave(HOST, 1);
        answer(1, 200, ticket("viewer", VIEWER, "session_one_0000"));
        answer(2, 404, "{\"detail\":\"Unknown room\"}");
        assertInstanceOf(P2p.Notice.class, viewer.getFirst());
        assertInstanceOf(P2p.Notice.class, other.getFirst());
        assertEquals(List.of("DELETE p2p/sessions/session_one_0000", "DELETE p2p/sessions/session_one_0000/peers/" + VIEWER),
                pending(), "the room and the unused seat are revoked");
    }

    @Test
    void aLateFailureOfAReplacedRequestSaysNothing() {
        host(1, "session_one_0000");
        join(1);
        join(2);
        calls.get(1).answer().completeExceptionally(new RuntimeException("timed out"));
        assertEquals(List.of(), viewer);
        answer(2, 200, ticket("viewer", VIEWER, "session_one_0000"));
        assertEquals(1, viewer.size());
        assertEquals(2, ((P2p.Ticket) viewer.getFirst()).request());
    }

    @Test
    void loggingOutRevokesWhatComesLateAndReloggingWaitsForIt() {
        host(1, "session_one_0000");
        join(1);
        P2p.logout(VIEWER);
        answer(1, 200, ticket("viewer", VIEWER, "session_one_0000"));
        assertEquals(List.of("DELETE p2p/sessions/session_one_0000/peers/" + VIEWER), pending());
        List<CustomPacketPayload> relogged = new ArrayList<>();
        P2p.request(player(VIEWER, relogged), new P2p.Request(P2p.Action.JOIN, 1, "Host"), HOST);
        answer(2, 204, "");
        answer(3, 200, ticket("viewer", VIEWER, "session_one_0000"));
        assertEquals(List.of(), viewer);
        assertInstanceOf(P2p.Ticket.class, relogged.getFirst());
    }

    @Test
    void stoppingRevokesEveryRoomAndPendingTicket() throws Exception {
        host(1, "session_one_0000");
        join(1);
        Thread stopping = Thread.ofPlatform().start(P2p::stop);
        await(() -> pending().contains("DELETE p2p/sessions/session_one_0000"));
        answer(1, 200, ticket("viewer", VIEWER, "session_one_0000"));
        assertTrue(pending().contains("DELETE p2p/sessions/session_one_0000/peers/" + VIEWER));
        assertTrue(stopping.isAlive(), "waits for the revocations");
        for (int i = 0; i < calls.size(); i++) {
            calls.get(i).answer().complete(new P2p.Answer(204, ""));
        }
        stopping.join(5000);
        assertEquals(List.of(), viewer);
        host.clear();
        P2p.request(player(HOST, host), new P2p.Request(P2p.Action.HOST, 2, ""), null);
        assertInstanceOf(P2p.Notice.class, host.getFirst(), "nothing opens while stopping");
    }

    private void host(int request, String session) {
        P2p.request(player(HOST, host), new P2p.Request(P2p.Action.HOST, request, ""), null);
        answer(calls.size() - 1, 200, ticket("host", HOST, session));
    }

    private void join(int request) {
        P2p.request(player(VIEWER, viewer), new P2p.Request(P2p.Action.JOIN, request, "Host"), HOST);
    }

    private static P2p.Player player(UUID id, List<CustomPacketPayload> sent) {
        return new P2p.Player(id, () -> false, sent::add);
    }

    private void answer(int call, int status, String body) {
        calls.get(call).answer().complete(new P2p.Answer(status, body));
    }

    /** The calls not answered yet, as "METHOD path". */
    private List<String> pending() {
        return calls.stream().filter(c -> !c.answer().isDone()).map(c -> c.method() + " " + c.path()).toList();
    }

    private static String ticket(String role, UUID peer, String session) {
        return P2pTest.ticket(role, peer, "").replace("abcdefgh12345678", session);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; !condition.getAsBoolean(); i++) {
            assertTrue(i < 250, "timed out");
            Thread.sleep(20);
        }
    }
}
