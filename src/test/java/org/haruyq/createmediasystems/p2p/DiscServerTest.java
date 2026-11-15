package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.haruyq.createmediasystems.disc.DiscSource;
import org.haruyq.createmediasystems.disc.DiscStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The server a disc's segments reach this computer's player through (DiscStream#prepare). */
@EnabledOnOs(value = OS.WINDOWS, architectures = {"amd64", "x86_64"})
class DiscServerTest {
    /** A Blu-ray's segment at 1080p60: 4 s of 8000 kbps. */
    private static final byte[] SEGMENT = new byte[4 << 20];
    private final HttpClient client = HttpClient.newHttpClient();
    private Transport transport;
    private DiscStream stream;

    @BeforeEach
    void open() throws Exception {
        Transport.loadNatives(Path.of("build", "p2p-test-natives"));
        transport = new Transport(Protocol.Ticket.parse(P2pTest.ticket("host", P2pTest.HOST, "")),
                message -> {}, message -> {}, () -> {});
        // 75 segments. Nothing reads it: the segments asked for are there already (see have).
        stream = new DiscStream(transport, UUID.randomUUID(), new DiscSource("unused", Path.of("."), true, 1, 300, null),
                message -> {});
        for (int i = 0; i < SEGMENT.length; i++) {
            SEGMENT[i] = (byte) (i * 31 + (i >> 8));
        }
    }

    @AfterEach
    void close() {
        stream.close();
        transport.close();
    }

    @Test
    void aPlayerThatLeavesMidSegmentLeavesNothingBehind() throws Exception {
        have(CompletableFuture.completedFuture(SEGMENT), 0, 1, 2, 3, 4);
        URI index = stream.prepare(0);
        String request = "GET " + index.getRawPath().replace("index.m3u8", "1.ts") + " HTTP/1.1\r\nHost: x\r\n\r\n";
        long before = used();
        // A player seeking or stopping: it asks for a segment, takes some of it and goes. The server this one took
        // the place of kept 9 MB for each of them, for good: ten were 90 MB, and these 2.6 GB.
        for (int i = 1; i <= 300; i++) {
            try (Socket socket = new Socket("127.0.0.1", index.getPort())) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                assertEquals(65536, socket.getInputStream().readNBytes(65536).length);
            }
            if (i % 10 == 0) {
                long kept = used() - before;
                assertTrue(kept < 64L << 20, "Kept " + (kept >> 20) + " MB for " + i + " players that are gone");
            }
        }
        assertEquals(200, ask("GET", index).statusCode());
    }

    @Test
    void answersItsPlaylistAndItsSegmentsOnly() throws Exception {
        have(CompletableFuture.completedFuture(SEGMENT), 0, 1, 2, 3, 4);
        URI index = stream.prepare(0);
        HttpResponse<byte[]> list = ask("GET", index);
        assertEquals(200, list.statusCode());
        assertEquals("application/vnd.apple.mpegurl", list.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", list.headers().firstValue("Cache-Control").orElseThrow());
        String[] lines = new String(list.body(), StandardCharsets.UTF_8).split("\n");
        assertEquals("74.ts", lines[lines.length - 2]);
        HttpResponse<byte[]> segment = ask("GET", index.resolve("1.ts"));
        assertEquals(200, segment.statusCode());
        assertEquals("video/mp2t", segment.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", segment.headers().firstValue("Cache-Control").orElseThrow());
        assertArrayEquals(SEGMENT, segment.body());
        assertEquals(404, ask("GET", index.resolve("75.ts")).statusCode());
        assertEquals(404, ask("GET", index.resolve("1.mp4")).statusCode());
        assertEquals(404, ask("GET", index.resolve("../" + UUID.randomUUID() + "/index.m3u8")).statusCode());
        assertEquals(405, ask("POST", index).statusCode());
        assertEquals(405, ask("GET", index.resolve("1.ts?again")).statusCode());
    }

    @Test
    void readsAheadOfASegmentAskedForAndDropsWhatAPlayerLeft() throws Exception {
        // With its encoder busy, what is read ahead stays as it was queued.
        CountDownLatch busy = new CountDownLatch(1);
        ((ThreadPoolExecutor) field("encoder")).execute(() -> {
            busy.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException closed) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        have(CompletableFuture.completedFuture(SEGMENT), 0, 1, 2, 39, 40);
        URI index = stream.prepare(0);
        assertEquals(200, ask("GET", index.resolve("1.ts")).statusCode());
        assertEquals(Set.of(0, 1, 2, 3, 4, 39, 40), segments());
        // A seek: the three read ahead of where it was give way to those ahead of where it is.
        assertEquals(200, ask("GET", index.resolve("40.ts")).statusCode());
        assertEquals(Set.of(0, 1, 2, 39, 40, 41, 42, 43), segments());
    }

    @Test
    void answersEightRequestsAtATime() throws Exception {
        have(CompletableFuture.completedFuture(SEGMENT), 0, 1, 2);
        CompletableFuture<byte[]> encoding = new CompletableFuture<>();
        have(encoding, 5);
        URI index = stream.prepare(0);
        List<CompletableFuture<HttpResponse<byte[]>>> waiting = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            waiting.add(client.sendAsync(HttpRequest.newBuilder(index.resolve("5.ts")).build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
        }
        Semaphore slots = (Semaphore) field("httpSlots");
        for (long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); slots.availablePermits() > 0;) {
            assertTrue(System.nanoTime() < until, "Eight requests are waiting for their segment");
            Thread.sleep(10);
        }
        assertEquals(503, ask("GET", index).statusCode());
        encoding.complete(SEGMENT);
        for (var answered : waiting) {
            assertArrayEquals(SEGMENT, answered.get(10, TimeUnit.SECONDS).body());
        }
        assertEquals(200, ask("GET", index).statusCode());
    }

    @Test
    void closingTheStreamLetsGoOfAPlayerMidSegment() throws Exception {
        // More than a connection takes in before its player reads it, as a paused player's does not.
        byte[] large = new byte[64 << 20];
        have(CompletableFuture.completedFuture(large), 0, 1, 2, 3, 4);
        URI index = stream.prepare(0);
        try (Socket socket = new Socket("127.0.0.1", index.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + index.getRawPath().replace("index.m3u8", "1.ts")
                    + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(65536, socket.getInputStream().readNBytes(65536).length);
            stream.close();
            int rest;
            try {
                rest = socket.getInputStream().readAllBytes().length;
            } catch (java.net.SocketTimeoutException held) {
                throw new AssertionError("The player's connection is still open", held);
            } catch (IOException reset) {
                rest = 0;
            }
            assertTrue(rest < large.length - 65536, "The rest of the segment was not sent");
        }
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", index.getPort()).close());
    }

    private HttpResponse<byte[]> ask(String method, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static long used() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private Object field(String name) throws Exception {
        var field = DiscStream.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(stream);
    }

    private Set<?> segments() throws Exception {
        synchronized (stream) {
            return Set.copyOf(((Map<?, ?>) field("segments")).keySet());
        }
    }

    /** The stream has {@code bytes} as each of its segments {@code indexes}, as encoded or as still being encoded. */
    @SuppressWarnings("unchecked")
    private void have(CompletableFuture<byte[]> bytes, int... indexes) throws Exception {
        var cached = Class.forName(DiscStream.class.getName() + "$Cached").getDeclaredConstructors()[0];
        cached.setAccessible(true);
        synchronized (stream) {
            for (int index : indexes) {
                ((Map<Integer, Object>) field("segments")).put(index,
                        cached.newInstance(new DiscSource.Quality(1080, 60, 8000), bytes, (Runnable) () -> {}));
            }
        }
    }
}
