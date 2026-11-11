package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The client's requests and tickets, with sessions that fail to open when each test says. */
class P2pClientTest {
    final List<CustomPacketPayload> sent = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<Void>> opening = new CopyOnWriteArrayList<>();
    final AtomicInteger ran = new AtomicInteger();
    final List<String> origins = new CopyOnWriteArrayList<>();

    @BeforeEach
    void client() {
        P2pClient.game = task -> {
            task.run();
            ran.incrementAndGet();
        };
        P2pClient.server = sent::add;
        // Every open fails, once its test lets it.
        P2pClient.api = () -> "https://a.example";
        P2pClient.open = (api, ticket, listener) -> {
            CompletableFuture<Void> failure = new CompletableFuture<>();
            opening.add(failure);
            failure.join();
            origins.add(api);
            throw new IllegalStateException("no natives here");
        };
        P2pClient.logout();
    }

    @Test
    void aTicketForALeftRequestIsNotOpened() {
        int request = host();
        P2pClient.leave();
        assertEquals(List.of(request), leaves());
        P2pClient.ticket(ticket(request));
        assertEquals(List.of(), opening);
    }

    @Test
    void aReplacedSessionFailingLateLeavesNothing() throws Exception {
        int first = host();
        P2pClient.ticket(ticket(first));
        int second = host();
        P2pClient.ticket(ticket(second));
        await(() -> opening.size() == 2);
        fail(0);
        assertEquals(List.of(), leaves(), "the second session is not left for the first's failure");
        fail(1);
        assertEquals(List.of(second), leaves());
    }

    @Test
    void anOldSessionEndingWhileANewRequestIsPendingLeavesOnlyItself() throws Exception {
        int first = host();
        P2pClient.ticket(ticket(first));
        int second = host();
        await(() -> opening.size() == 1);
        // The server ended the first session for the second request: its leave names the first, which the server ignores.
        fail(0);
        assertEquals(List.of(first), leaves());
        P2pClient.ticket(ticket(second));
        await(() -> opening.size() == 2);
        fail(1);
        assertEquals(List.of(first, second), leaves());
    }

    @Test
    void loggingOutDropsWhatIsInFlight() throws Exception {
        int request = host();
        P2pClient.ticket(ticket(request));
        await(() -> opening.size() == 1);
        P2pClient.logout();
        fail(0);
        P2pClient.ticket(ticket(request));
        assertEquals(List.of(), leaves());
        assertEquals(1, opening.size());
    }

    @Test
    void aRoomOfItsOwnAndAnothersOpenSideBySide() throws Exception {
        // This client is the viewer of the tickets: it hosts a room of its own and joins the host's.
        P2pClient.request(P2p.Action.HOST, P2pTest.VIEWER, "");
        int own = ((P2p.Request) sent.getLast()).id();
        P2pClient.request(P2p.Action.JOIN, P2pTest.HOST, "Host");
        int joined = ((P2p.Request) sent.getLast()).id();
        P2pClient.ticket(new P2p.Ticket(Protocol.Ticket.parse(P2pTest.ticket("host", P2pTest.VIEWER, "")
                .replace("\"host_id\":\"" + P2pTest.HOST, "\"host_id\":\"" + P2pTest.VIEWER)), own));
        P2pClient.ticket(new P2p.Ticket(Protocol.Ticket.parse(P2pTest.ticket("viewer", P2pTest.VIEWER, "")), joined));
        await(() -> opening.size() == 2);
        fail(1);
        assertEquals(List.of(joined), leaves(), "the joined room ending leaves the one it hosts alone");
        fail(0);
        assertEquals(List.of(joined, own), leaves());
    }

    @Test
    void aRefusedRequestIsAskedForNoLonger() {
        P2pClient.request(P2p.Action.JOIN, P2pTest.HOST, "Host");
        int refused = ((P2p.Request) sent.getLast()).id();
        int own = hostOwn();
        P2pClient.notice(net.minecraft.network.chat.Component.empty(), refused);
        P2pClient.ticket(new P2p.Ticket(Protocol.Ticket.parse(P2pTest.ticket("viewer", P2pTest.VIEWER, "")), refused));
        assertEquals(List.of(), opening, "its ticket, were one to come, opens nothing");
        P2pClient.leave();
        assertEquals(List.of(own), leaves(), "and there is nothing of it to leave");
    }

    @Test
    void aTicketOpensAtItsOwnServersApiOnly() throws Exception {
        int request = host();
        P2pClient.ticket(ticket(request));
        await(() -> opening.size() == 1);
        // Joined another server, with another API, while natives were still unpacking.
        P2pClient.api = () -> "https://b.example";
        fail(0);
        assertEquals(List.of("https://a.example"), origins);
    }

    private int host() {
        P2pClient.request(P2p.Action.HOST, P2pTest.HOST, "");
        return ((P2p.Request) sent.getLast()).id();
    }

    private int hostOwn() {
        P2pClient.request(P2p.Action.HOST, P2pTest.VIEWER, "");
        return ((P2p.Request) sent.getLast()).id();
    }

    private static P2p.Ticket ticket(int request) {
        return new P2p.Ticket(Protocol.Ticket.parse(P2pTest.ticket("host", P2pTest.HOST, "")), request);
    }

    /** Lets the {@code i}th open fail, and waits for its outcome on the game thread. */
    private void fail(int i) throws InterruptedException {
        int before = ran.get();
        opening.get(i).complete(null);
        await(() -> ran.get() > before);
    }

    private List<Integer> leaves() {
        return sent.stream().map(p -> (P2p.Request) p).filter(r -> r.action() == P2p.Action.LEAVE).map(P2p.Request::id).toList();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; !condition.getAsBoolean(); i++) {
            assertTrue(i < 250, "timed out");
            Thread.sleep(20);
        }
    }
}
