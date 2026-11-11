package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Two real native WebRTC peers in this process, signalling through a stand-in for the API that forwards as it does
 * (to → from): checks the probe each connection is measured with, the bytes that cross their DataChannel, pacing and
 * backpressure, and closing.
 * Natives ship for 64-bit Windows only, so the tests that load them run only there.
 */
class TransportTest {
    static final UUID HOST = P2pTest.HOST;
    static final UUID VIEWER = P2pTest.VIEWER;

    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    void sendsBytesDirectlyAtTheBudgetAndCloses() throws Exception {
        // Not a temporary directory: Windows can't delete a loaded library.
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        List<String> said = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        Transport[] peers = new Transport[2];
        Transport host = peers[0] = new Transport(Protocol.Ticket.parse(P2pTest.ticket("host", HOST, "")), said::add,
                text -> forward(text, HOST, peers[1]), () -> { });
        Transport viewer = peers[1] = new Transport(Protocol.Ticket.parse(P2pTest.ticket("viewer", VIEWER, "")), new Transport.Listener() {
            @Override
            public void data(UUID from, byte[] payload) {
                assertEquals(HOST, from);
                synchronized (got) {
                    got.writeBytes(payload);
                }
            }

            @Override
            public void status(String message) {
                said.add(message);
            }
        }, text -> forward(text, VIEWER, peers[0]), () -> { });
        try {
            assertFalse(host.send(new byte[1]), "no viewer yet");
            viewer.signal(peers(HOST));
            host.signal(peers(HOST, VIEWER));
            await(() -> host.connected().equals(List.of(VIEWER)) && viewer.connected().equals(List.of(HOST)), 20_000, said);
            assertFalse(viewer.send(new byte[1]), "only the host sends");
            await(() -> host.budgetBps() != LinkRate.INITIAL_BPS && viewer.budgetBps() > 0, 10_000, said);
            // In-process, the probe measures far more than real connections carry: pace this one at 8 Mbit/s.
            synchronized (host) {
                host.links.get(VIEWER).rate.probed(1_250_000, 1_000_000_000L);
            }
            await(() -> host.budgetBps() == 8_000_000, 1_000, said);
            assertEquals(0, viewer.received(), "the probe is not data");

            byte[] data = new byte[2 << 20];
            new Random(1).nextBytes(data);
            long start = System.nanoTime();
            boolean refused = false;
            long backlog = 0;
            for (int at = 0; at < data.length; ) {
                backlog = Math.max(backlog, host.backlog());
                int n = Math.min(Transport.MAX_PAYLOAD, data.length - at);
                byte[] chunk = java.util.Arrays.copyOfRange(data, at, at + n);
                if (host.send(chunk)) {
                    at += n;
                } else {
                    refused = true;
                    Thread.sleep(5);
                }
            }
            await(() -> {
                synchronized (got) {
                    return got.size() == data.length;
                }
            }, 20_000);
            double seconds = (System.nanoTime() - start) / 1e9;
            synchronized (got) {
                assertArrayEquals(data, got.toByteArray());
            }
            assertTrue(refused, "a full queue pushes back");
            assertTrue(backlog > Transport.MAX_QUEUED / 2, "a full queue shows as backlog, saw " + backlog);
            await(() -> host.backlog() == 0, 5_000);
            // 16 Mbit at 8 Mbit/s, less the first burst.
            assertTrue(seconds > 1.6, "paced at the budget, took " + seconds + " s");

            host.ping();
            await(() -> said.stream().anyMatch(s -> s.contains("ms over a direct")), 5_000);
        } finally {
            host.close();
            viewer.close();
        }
        await(() -> host.state() == Transport.State.CLOSED && viewer.state() == Transport.State.CLOSED, 5_000);
        long received = viewer.received();
        assertFalse(host.send(new byte[1]));
        assertEquals(0, host.backlog());
        viewer.signal(peers(HOST));
        Thread.sleep(200);
        assertEquals(received, viewer.received());
        assertEquals(Transport.State.CLOSED, viewer.state(), "nothing reopens a closed session");
    }

    /** A peer that sends a frame too large, or more than a busy listener keeps up with, is dropped once and holds nothing. */
    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    void dropsAPeerThatSendsTooMuchOnce() throws Exception {
        UUID other = new UUID(0, 3);
        Semaphore busy = new Semaphore(0);
        List<String> said = new CopyOnWriteArrayList<>();
        Map<UUID, Transport.Link> links = new HashMap<>();
        Transport host = busyHost(busy, said, links, HOST, VIEWER, other);
        try {
            Transport.Link a = links.get(VIEWER), b = links.get(other);
            int cost = Transport.MAX_PAYLOAD + 1 + Transport.FRAME_COST;
            a.take(ByteBuffer.allocate(Transport.MAX_PAYLOAD + 1));
            a.take(ByteBuffer.allocate(Transport.MAX_PAYLOAD + 2));
            assertEquals(cost, host.inbound.get(), "a frame too large reserves nothing");
            a.take(ByteBuffer.allocate(1));
            assertEquals(cost, host.inbound.get(), "nor does anything after it");
            for (int i = 0; i < 100; i++) {
                b.take(ByteBuffer.allocate(Transport.MAX_PAYLOAD + 1));
            }
            assertEquals(Transport.MAX_INBOUND / cost * cost, host.inbound.get(), "up to the bound, no further");
            busy.release();
            await(() -> host.inbound.get() == 0, 5_000, said);
            await(() -> said.stream().filter(s -> s.contains("sent more than")).count() == 2, 5_000, said);
            synchronized (host) {
                assertTrue(host.links.isEmpty());
            }
        } finally {
            busy.release();
            host.close();
        }
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    void releasesWhatClosingLeavesUndelivered() throws Exception {
        Semaphore busy = new Semaphore(0);
        List<String> said = new CopyOnWriteArrayList<>();
        Map<UUID, Transport.Link> links = new HashMap<>();
        Transport host = busyHost(busy, said, links, HOST, VIEWER);
        try {
            Transport.Link link = links.get(VIEWER);
            host.close();
            // Comes in after the close, still queued behind the listener.
            link.take(ByteBuffer.allocate(1));
            assertEquals(1 + Transport.FRAME_COST, host.inbound.get());
            busy.release();
            await(() -> host.state() == Transport.State.CLOSED && host.inbound.get() == 0, 5_000, said);
            link.take(ByteBuffer.allocate(1));
            assertEquals(0, host.inbound.get(), "nothing is taken once closed");
        } finally {
            busy.release();
            host.close();
        }
    }

    /**
     * A host linked to the viewers among {@code ids} (put in {@code links}), whose listener then holds the session's
     * thread, and with it the Transport's monitor, as a slow one would: until {@code busy} is released, or 30 s.
     */
    private static Transport busyHost(Semaphore busy, List<String> said, Map<UUID, Transport.Link> links, UUID... ids)
            throws Exception {
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        Transport host = new Transport(Protocol.Ticket.parse(P2pTest.ticket("host", HOST, "")), message -> {
            said.add(message);
            if (message.startsWith("No peer")) {
                try {
                    busy.tryAcquire(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, text -> { }, () -> { });
        host.signal(peers(ids));
        await(() -> {
            synchronized (host) {
                return host.links.size() == ids.length - 1;
            }
        }, 5_000, said);
        synchronized (host) {
            links.putAll(host.links);
        }
        host.ping();
        await(() -> said.contains("No peer is connected directly yet."), 5_000, said);
        return host;
    }

    /**
     * Run alone, with LOCALAPPDATA pointed somewhere to unpack into (CMS_P2P_UNSPELLABLE_DIR set): natives asked for
     * in a folder this Java can't spell go there instead, and load. Natives load once per JVM, hence alone.
     */
    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    @EnabledIfEnvironmentVariable(named = "CMS_P2P_UNSPELLABLE_DIR", matches = ".+")
    void loadsNativesAskedForInAFolderThisJavaCannotSpell() throws Exception {
        Path asked = Path.of(System.getenv("CMS_P2P_UNSPELLABLE_DIR"), "한글");
        assertFalse(java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding")).newEncoder()
                .canEncode(asked.toAbsolutePath().toString()), "needs a system code page without Hangul");
        Transport.loadNatives(asked);
        String used = System.getProperty("webrtc.java.library.dir");
        assertTrue(used.startsWith(System.getenv("LOCALAPPDATA")), used);
        new dev.onvoid.webrtc.PeerConnectionFactory().dispose();
    }

    @Test
    void abortsASignalingSocketThatOpensAfterClosing() {
        List<String> calls = new CopyOnWriteArrayList<>();
        WebSocket late = (WebSocket) Proxy.newProxyInstance(TransportTest.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });
        Transport.Socket socket = new Transport.Socket(HttpClient.newHttpClient(), URI.create("ws://127.0.0.1:1/"), "ticket");
        socket.close();
        socket.onOpen(late);
        assertEquals(List.of("abort"), calls);
    }

    /** A proxy in front of the API cuts the socket; the session's direct connections carry on, so it must too. */
    @Test
    void opensSignalingAgainOnceItIsLost() throws Exception {
        List<String> tickets = new CopyOnWriteArrayList<>(), got = new CopyOnWriteArrayList<>(), failed = new CopyOnWriteArrayList<>();
        try (ServerSocket api = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            api.setSoTimeout(10_000);
            Transport.Socket socket = new Transport.Socket(HttpClient.newHttpClient(),
                    URI.create("ws://127.0.0.1:" + api.getLocalPort() + "/signal"), "ticket");
            socket.signal = got::add;
            socket.fail = failed::add;
            socket.retryMillis = 10;
            socket.connect();
            try (java.net.Socket cut = accept(api, failed)) {
                tickets.add(upgrade(cut, true));
            }
            try (java.net.Socket back = accept(api, failed)) {
                tickets.add(upgrade(back, true));
                back.getOutputStream().write(new byte[] {(byte) 0x81, 2, '{', '}'});
                await(() -> got.equals(List.of("{}")), 5_000, failed);
                socket.send("still here");
                assertEquals(0x81, back.getInputStream().read(), "what it signals goes to the new socket");
            }
            assertEquals(List.of(), failed);
            try (java.net.Socket gone = accept(api, failed)) {
                tickets.add(upgrade(gone, false));
                await(() -> failed.equals(List.of("The API ended this P2P session (HTTP 403).")), 5_000, failed);
            }
            assertEquals(List.of("Bearer ticket", "Bearer ticket", "Bearer ticket"), tickets);
            socket.close();
        }
    }

    private static java.net.Socket accept(ServerSocket api, List<String> failed) throws IOException {
        try {
            return api.accept();
        } catch (SocketTimeoutException absent) {
            throw new AssertionError("Signaling did not connect: " + failed, absent);
        }
    }

    /** Answers a WebSocket's opening request as the API does, or refuses it; returns the ticket it came with. */
    private static String upgrade(java.net.Socket socket, boolean accept) throws Exception {
        BufferedReader request = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        Map<String, String> headers = new HashMap<>();
        for (String line = request.readLine(); line != null && !line.isEmpty(); line = request.readLine()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        String key = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                (headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        socket.getOutputStream().write((accept
                ? "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + key + "\r\n\r\n"
                : "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return headers.get("authorization");
    }

    /** The same through a running CMS-API (CMS_P2P_API_URL, CMS_P2P_SERVER_TOKEN), as the server and clients use it. */
    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    @EnabledIfEnvironmentVariable(named = "CMS_P2P_API_URL", matches = ".+")
    void connectsThroughTheApi() throws Exception {
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        String api = System.getenv("CMS_P2P_API_URL");
        String secret = System.getenv("CMS_P2P_SERVER_TOKEN");
        Protocol.Ticket hostTicket = Protocol.Ticket.parse(post(api, "p2p/sessions", secret, "host_id", HOST));
        Protocol.Ticket viewerTicket = Protocol.Ticket.parse(post(api, "p2p/sessions/" + hostTicket.sessionId() + "/peers", secret, "peer_id", VIEWER));
        List<String> said = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        Transport host = Transport.open(api, hostTicket, said::add);
        Transport viewer = Transport.open(api, viewerTicket, new Transport.Listener() {
            @Override
            public void data(UUID from, byte[] payload) {
                synchronized (got) {
                    got.writeBytes(payload);
                }
            }

            @Override
            public void status(String message) {
                said.add(message);
            }
        });
        try {
            await(() -> host.connected().equals(List.of(VIEWER)) && viewer.connected().equals(List.of(HOST)), 20_000, said);
            await(() -> host.budgetBps() != LinkRate.INITIAL_BPS && viewer.budgetBps() > 0, 10_000, said);
            byte[] data = new byte[256 * 1024];
            new Random(2).nextBytes(data);
            for (int at = 0; at < data.length; at += Transport.MAX_PAYLOAD) {
                assertTrue(host.send(java.util.Arrays.copyOfRange(data, at, at + Transport.MAX_PAYLOAD)));
            }
            await(() -> {
                synchronized (got) {
                    return got.size() == data.length;
                }
            }, 10_000, said);
            synchronized (got) {
                assertArrayEquals(data, got.toByteArray());
            }
            host.close();
            await(() -> viewer.state() == Transport.State.CLOSED, 10_000, said);
        } finally {
            host.close();
            viewer.close();
        }
        HttpResponse<String> deleted = P2p.HTTP.send(HttpRequest.newBuilder(
                        Protocol.endpoint(api, "p2p/sessions/" + hostTicket.sessionId(), false))
                .header("Authorization", "Bearer " + secret).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, deleted.statusCode());
    }

    /**
     * Through a running CMS-API again, by way of a proxy that cuts every signaling socket: the host keeps sending to
     * its viewer meanwhile, and a viewer that joins afterwards is signalled over the sockets opened in their place.
     */
    @Test
    @EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
    @EnabledIfEnvironmentVariable(named = "CMS_P2P_API_URL", matches = ".+")
    void keepsItsConnectionsWhileSignalingComesBack() throws Exception {
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        String api = System.getenv("CMS_P2P_API_URL");
        String secret = System.getenv("CMS_P2P_SERVER_TOKEN");
        UUID late = UUID.randomUUID();
        Protocol.Ticket hostTicket = Protocol.Ticket.parse(post(api, "p2p/sessions", secret, "host_id", HOST));
        String peers = "p2p/sessions/" + hostTicket.sessionId() + "/peers";
        Protocol.Ticket viewerTicket = Protocol.Ticket.parse(post(api, peers, secret, "peer_id", VIEWER));
        List<String> said = new CopyOnWriteArrayList<>();
        List<byte[]> got = new CopyOnWriteArrayList<>();
        Transport.Listener listener = new Transport.Listener() {
            @Override
            public void data(UUID from, byte[] payload) {
                got.add(payload);
            }

            @Override
            public void status(String message) {
                said.add(message);
            }
        };
        try (Cut proxy = new Cut(URI.create(api))) {
            Transport host = Transport.open(proxy.url, hostTicket, said::add);
            Transport viewer = Transport.open(proxy.url, viewerTicket, listener);
            Transport joined = null;
            try {
                await(() -> host.connected().equals(List.of(VIEWER)) && viewer.connected().equals(List.of(HOST)), 20_000, said);
                proxy.cut();
                assertTrue(host.send(new byte[] {7}));
                await(() -> got.size() == 1, 10_000, said);
                Protocol.Ticket lateTicket = Protocol.Ticket.parse(post(api, peers, secret, "peer_id", late));
                Transport other = joined = Transport.open(proxy.url, lateTicket, listener);
                await(() -> host.connected().equals(List.of(VIEWER, late)) && other.connected().equals(List.of(HOST)), 30_000, said);
                assertEquals(Transport.State.CONNECTED, viewer.state(), said.toString());
            } finally {
                host.close();
                viewer.close();
                if (joined != null) {
                    joined.close();
                }
            }
        }
        P2p.HTTP.send(HttpRequest.newBuilder(Protocol.endpoint(api, "p2p/sessions/" + hostTicket.sessionId(), false))
                .header("Authorization", "Bearer " + secret).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Forwards TCP to the API, as a proxy in front of it does, and cuts what it forwards on demand. */
    private static final class Cut implements AutoCloseable {
        final String url;
        private final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        private final List<java.net.Socket> open = new CopyOnWriteArrayList<>();

        Cut(URI api) throws IOException {
            url = "http://127.0.0.1:" + server.getLocalPort() + api.getRawPath();
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        java.net.Socket in = server.accept(), out = new java.net.Socket(api.getHost(), api.getPort());
                        open.add(in);
                        open.add(out);
                        pipe(in, out);
                        pipe(out, in);
                    }
                } catch (IOException closed) {
                }
            });
        }

        private static void pipe(java.net.Socket from, java.net.Socket to) {
            Thread.ofVirtual().start(() -> {
                try (from; to) {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (IOException cut) {
                }
            });
        }

        void cut() throws IOException {
            for (java.net.Socket socket : open) {
                socket.close();
            }
            open.clear();
        }

        @Override
        public void close() throws IOException {
            server.close();
            cut();
        }
    }

    private static String post(String api, String path, String secret, String idField, UUID id) throws Exception {
        HttpResponse<String> response = P2p.HTTP.send(HttpRequest.newBuilder(Protocol.endpoint(api, path, false))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"" + idField + "\":\"" + id
                        + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode() / 100 * 100, response.body());
        return response.body();
    }

    /** What the API does with a message from {@code from}: checks it is for {@code to}'s peer, and says who sent it. */
    private static void forward(String text, UUID from, Transport to) {
        JsonObject message = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(from.equals(HOST) ? VIEWER.toString() : HOST.toString(), message.remove("to").getAsString());
        assertTrue(text.length() <= 64 * 1024);
        message.addProperty("from", from.toString());
        to.signal(message.toString());
    }

    private static String peers(UUID... ids) {
        StringBuilder peers = new StringBuilder();
        for (UUID id : ids) {
            peers.append(peers.isEmpty() ? "" : ",").append("{\"peer_id\":\"").append(id).append("\",\"role\":\"")
                    .append(id.equals(HOST) ? "host" : "viewer").append("\"}");
        }
        return "{\"type\":\"peers\",\"host_id\":\"" + HOST + "\",\"peers\":[" + peers + "],\"budget_bps\":0}";
    }

    private static void await(BooleanSupplier condition, long millis, Object... context) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < end, () -> "timed out " + java.util.Arrays.toString(context));
            Thread.sleep(20);
        }
    }
}
