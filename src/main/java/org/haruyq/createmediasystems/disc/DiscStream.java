package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.p2p.Transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class DiscStream implements AutoCloseable {
    private static final int MAGIC = 0x434d4401;
    private static final int HEADER = 44;
    private static final int CACHE_BYTES = 64 * 1024 * 1024;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private final Transport transport;
    private final UUID sourceId;
    private final DiscSource source;
    private final Consumer<String> status;
    private final LinkedBlockingDeque<Runnable> work = new LinkedBlockingDeque<>(16);
    private final ThreadPoolExecutor encoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, work,
            Thread.ofPlatform().daemon().name("CMS disc encoder").factory());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Integer, Cached> segments = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<UUID, ArrayDeque<Request>> serving = new HashMap<>();
    private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();
    private final Object reception = new Object();
    private final Object fetchLock = new Object();
    private final Semaphore httpSlots = new Semaphore(8);
    private final Adaptive adaptive = new Adaptive();
    private final HttpServer http;
    private final String prefix = "/" + UUID.randomUUID() + "/";
    private volatile double duration;
    private volatile boolean closed;
    private Pending pending;
    private volatile String codec;
    /** For {@link #stats}: see there. */
    private volatile int waitingIndex, lastIndex = -1, lastEncoded = -1, failures;
    private volatile long waitingSince, lastBytes, lastNanos;
    private volatile String lastError, lastQuality;
    /** The DVD pass segments are taken from; on the encoder thread, and closed by close. */
    private volatile DiscRun run;
    private int cachedBytes;

    public DiscStream(Transport transport, UUID sourceId, DiscSource source, Consumer<String> status) throws IOException {
        this.transport = transport;
        this.sourceId = sourceId;
        this.source = source;
        this.status = status;
        duration = source == null ? 0 : source.duration();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        http.createContext(prefix, this::serve);
        http.setExecutor(workers);
        http.start();
        encoder.prestartCoreThread();
    }

    public boolean matches(Transport session, UUID id) {
        return !closed && session == transport && sourceId.equals(id);
    }

    /**
     * What this stream did last, for the debug overlay. As viewer: the segment it waits for from the host (-1: the
     * disc's length) and since when (0: none), the last one it got, its size and how long it took, and how many
     * requests failed and why the last did. As host: its encoder, and the last segment it encoded and at what quality.
     */
    public record Stats(boolean host, int waiting, long waitingSince, int last, long lastBytes, long lastNanos,
                        int failures, String error, String encoder, int encoded, String quality) {
    }

    public Stats stats() {
        return new Stats(source != null, waitingIndex, waitingSince, lastIndex, lastBytes, lastNanos, failures, lastError,
                codec, lastEncoded, lastQuality);
    }

    public double duration() {
        return duration;
    }

    public URI prepare(double startSeconds) throws IOException {
        if (duration == 0) {
            fetch(-1);
        }
        int start = Math.min(count() - 1, Math.max(0, (int) (startSeconds / DiscSource.SEGMENT_SECONDS)));
        // Queued in order, at the back: each at the front put 2 before 1, and a DVD's pass (DiscRun) seeks again for
        // every segment taken out of order, jumping at the first boundaries.
        List<CompletableFuture<byte[]>> ready = new ArrayList<>();
        ready.add(segment(0, true));
        for (int i = start; i < Math.min(count(), start + 3); i++) {
            ready.add(segment(i, true));
        }
        for (var part : ready) {
            await(part);
        }
        return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + prefix + "index.m3u8");
    }

    private int count() {
        return (int) Math.ceil(duration / DiscSource.SEGMENT_SECONDS);
    }

    static String playlist(double duration) {
        if (!Double.isFinite(duration) || duration <= 0 || duration > DiscSource.MAX_DURATION) {
            throw new IllegalArgumentException("Invalid disc duration");
        }
        StringBuilder list = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (int i = 0; i < Math.ceil(duration / DiscSource.SEGMENT_SECONDS); i++) {
            list.append(String.format(Locale.ROOT, "#EXTINF:%.6f,\n%d.ts\n", Math.min(DiscSource.SEGMENT_SECONDS,
                    duration - i * DiscSource.SEGMENT_SECONDS), i));
        }
        return list.append("#EXT-X-ENDLIST\n").toString();
    }

    private void serve(HttpExchange exchange) throws IOException {
        if (!httpSlots.tryAcquire()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET") || exchange.getRequestURI().getRawQuery() != null) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getRawPath().substring(prefix.length());
            byte[] body;
            if (path.equals("index.m3u8")) {
                body = playlist(duration).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            } else if (path.matches("[0-9]{1,5}\\.ts")) {
                int index = Integer.parseInt(path.substring(0, path.length() - 3));
                if (index >= count()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                body = await(segment(index));
                for (int i = index + 1; i < Math.min(count(), index + 4); i++) {
                    segment(i, true);
                }
                exchange.getResponseHeaders().set("Content-Type", "video/mp2t");
            } else {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException failed) {
            if (!closed) {
                status.accept("Disc buffering failed: " + failed.getMessage());
            }
        } finally {
            httpSlots.release();
        }
    }

    private CompletableFuture<byte[]> segment(int index) {
        return segment(index, false);
    }

    private CompletableFuture<byte[]> segment(int index, boolean ahead) {
        return segment(index, ahead, false);
    }

    private synchronized CompletableFuture<byte[]> segment(int index, boolean ahead, boolean lowerForPeer) {
        if (closed || index < 0 || index >= count()) {
            return CompletableFuture.failedFuture(new IOException("Disc stream is closed or out of range"));
        }
        long measured = transport.budgetBps();
        long available = adaptive.budget(measured == 0 ? 13_000_000 : measured);
        DiscSource.Quality quality = source == null ? null : DiscSource.Quality.forBudget(available);
        Cached known = segments.get(index);
        if (known != null) {
            if (!ahead && !known.ready.isDone() && work.remove(known.work)) {
                work.addFirst(known.work);
            }
            // A DVD's segment again would restart its pass (see encode), so it goes as it is.
            if (!lowerForPeer || source == null || !source.segmented() || !known.ready.isDone()
                    || known.quality.kbps() <= quality.kbps()) {
                return known.ready;
            }
            if (!known.ready.isCompletedExceptionally()) {
                cachedBytes -= known.ready.getNow(new byte[0]).length;
            }
            segments.remove(index);
        }
        if (segments.values().stream().filter(f -> !f.ready.isDone()).count() >= 16) {
            return CompletableFuture.failedFuture(new IOException("Too many disc reads are pending"));
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                byte[] bytes;
                if (source == null) {
                    bytes = fetch(index);
                } else {
                    if (codec == null) {
                        codec = source.encoder();
                        status.accept("Disc encoder: " + codec);
                    }
                    if (available < 500_000) {
                        throw new IOException("Disc sharing needs at least 0.5 Mbps per viewer");
                    }
                    long started = System.nanoTime();
                    // A DVD pass starting (see encode) seeks first: seconds that say nothing of how fast this encodes.
                    boolean starts = !source.segmented() && (run == null || !run.continues(index, quality, codec));
                    try {
                        bytes = encode(index, quality);
                    } catch (IOException failed) {
                        if (codec.equals("libx264") || Thread.currentThread().isInterrupted()) {
                            throw failed;
                        }
                        codec = "libx264";
                        status.accept("Hardware encoding failed; using libx264 for this disc");
                        starts = !source.segmented();
                        bytes = encode(index, quality);
                    }
                    if (!starts) {
                        adaptive.encoded(System.nanoTime() - started, available);
                    }
                    DiscRun current = run;
                    DiscSource.Quality used = source.segmented() || current == null ? quality : current.quality;
                    // A CD has no picture to say the quality of.
                    boolean sound = source.cd() != null;
                    lastQuality = sound ? "192 kbps" : used.height() + "p" + used.fps() + " " + used.kbps() + " kbps";
                    lastEncoded = index;
                    status.accept("Disc segment " + index + ": " + (sound ? lastQuality
                            : used.height() + "p " + used.fps() + " fps, " + used.kbps() + " kbps"));
                }
                synchronized (this) {
                    if (closed) {
                        throw new IOException("Disc stream was closed");
                    }
                    cachedBytes += bytes.length;
                    result.complete(bytes);
                    var it = segments.entrySet().iterator();
                    while ((cachedBytes > CACHE_BYTES || segments.size() > 16) && it.hasNext()) {
                        var old = it.next();
                        if (old.getValue().ready.isDone() && !old.getValue().ready.isCompletedExceptionally() && old.getKey() != index) {
                            cachedBytes -= old.getValue().ready.getNow(new byte[0]).length;
                            it.remove();
                        }
                    }
                }
            } catch (IOException | RuntimeException failed) {
                result.completeExceptionally(failed);
                synchronized (this) {
                    segments.computeIfPresent(index, (key, current) -> current.ready == result ? null : current);
                }
            }
        };
        segments.put(index, new Cached(quality, result, task));
        if (ahead) {
            work.addLast(task);
        } else {
            work.addFirst(task);
        }
        return result;
    }

    /** On the encoder thread. A DVD's next segment comes from its running pass (see DiscSource#run). */
    private byte[] encode(int index, DiscSource.Quality quality) throws IOException {
        if (source.segmented()) {
            return source.encode(index, quality, codec);
        }
        DiscRun current = run;
        if (current == null || !current.continues(index, quality, codec)) {
            if (current != null) {
                current.close();
            }
            run = current = source.run(index, quality, codec);
            if (closed) {
                current.close();
                throw new IOException("Disc stream was closed");
            }
        }
        try {
            return current.take();
        } catch (IOException failed) {
            current.close();
            run = null;
            throw failed;
        }
    }

    private static byte[] await(CompletableFuture<byte[]> future) throws IOException {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Disc buffering was cancelled");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof IOException error) {
                throw error;
            }
            throw new IOException("Could not buffer the disc video");
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IOException("Disc buffering timed out; check the host and the direct connection");
        }
    }

    private byte[] fetch(int index) throws IOException {
        synchronized (fetchLock) {
            return fetchOne(index);
        }
    }

    private byte[] fetchOne(int index) throws IOException {
        if (index == -1 && duration > 0) {
            return new byte[0];
        }
        synchronized (reception) {
            if (pending != null) {
                throw new IOException("Another disc request is still pending");
            }
            pending = new Pending(REQUESTS.incrementAndGet(), index);
        }
        Pending request = pending;
        long started = System.nanoTime();
        waitingIndex = index;
        waitingSince = started;
        try {
            sendControl(control(1, request.id, index));
            byte[] bytes = await(request.result);
            if (index >= 0) {
                lastIndex = index;
                lastBytes = bytes.length;
                lastNanos = System.nanoTime() - started;
            }
            sendControl(control(2, request.id, index));
            return bytes;
        } catch (IOException failed) {
            failures++;
            lastError = failed.getMessage();
            throw failed;
        } finally {
            waitingSince = 0;
            synchronized (reception) {
                pending = null;
            }
        }
    }

    private byte[] control(int type, int request, int index) {
        return ByteBuffer.allocate(32).putInt(MAGIC).putLong(sourceId.getMostSignificantBits())
                .putLong(sourceId.getLeastSignificantBits()).putInt(type).putInt(request).putInt(index).array();
    }

    private void sendControl(byte[] message) throws IOException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!closed && transport.state() != Transport.State.CLOSED && System.nanoTime() < until) {
            if (transport.control(transport.hostId(), message)) {
                return;
            }
            nap();
        }
        throw new IOException("The disc host is not connected directly");
    }

    public void control(UUID peer, byte[] bytes) {
        if (source == null || closed || bytes.length != 32) {
            return;
        }
        ByteBuffer message = ByteBuffer.wrap(bytes);
        if (!ours(message)) {
            return;
        }
        int type = message.getInt(), request = message.getInt(), index = message.getInt();
        if (index < -1 || index >= count()) {
            return;
        }
        if (type == 2) {
            Delivery delivery = deliveries.get(peer);
            if (delivery != null && delivery.id == request && delivery.index == index && deliveries.remove(peer, delivery)) {
                if (index >= 0) {
                    transport.delivered(peer, delivery.bytes, System.nanoTime() - delivery.started);
                }
            }
        } else if (type == 1) {
            synchronized (this) {
                deliveries.keySet().retainAll(transport.connected());
                ArrayDeque<Request> queue = serving.get(peer);
                boolean start = queue == null;
                if (start) {
                    if (serving.size() >= 16 || closed) {
                        return;
                    }
                    queue = new ArrayDeque<>();
                    serving.put(peer, queue);
                }
                if (queue.size() >= 4) {
                    return;
                }
                queue.add(new Request(request, index));
                if (start) {
                    workers.execute(() -> servePeer(peer));
                }
            }
        }
    }

    private void servePeer(UUID peer) {
        while (!closed) {
            Request request;
            synchronized (this) {
                request = serving.get(peer).poll();
                if (request == null) {
                    serving.remove(peer);
                    return;
                }
            }
            try {
                byte[] data = request.index < 0 ? new byte[0] : await(segment(request.index, false, true));
                if (request.index >= 0) {
                    // An encode already in flight can still use the previous, higher budget.
                    data = await(segment(request.index, false, true));
                }
                deliveries.put(peer, new Delivery(request.id, request.index, data.length, System.nanoTime()));
                reply(peer, request.id, request.index, data);
            } catch (IOException failed) {
                try {
                    reply(peer, request.id, request.index, null);
                } catch (IOException disconnected) {
                }
            }
        }
    }

    private boolean ours(ByteBuffer message) {
        return message.getInt() == MAGIC && message.getLong() == sourceId.getMostSignificantBits()
                && message.getLong() == sourceId.getLeastSignificantBits();
    }

    private void reply(UUID peer, int request, int index, byte[] bytes) throws IOException {
        int total = bytes == null ? -1 : bytes.length;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        for (int offset = 0; offset < Math.max(1, total);) {
            int size = Math.max(0, Math.min(Transport.MAX_PAYLOAD - HEADER, total - offset));
            ByteBuffer message = ByteBuffer.allocate(HEADER + size).putInt(MAGIC)
                    .putLong(sourceId.getMostSignificantBits()).putLong(sourceId.getLeastSignificantBits())
                    .putInt(request).putInt(index).putInt(total).putInt(offset).putDouble(duration);
            if (size > 0) {
                message.put(bytes, offset, size);
            }
            while (!closed && transport.state() != Transport.State.CLOSED && System.nanoTime() < until) {
                if (transport.send(peer, message.array())) {
                    break;
                }
                if (!transport.connected().contains(peer)) {
                    throw new IOException("Disc viewer disconnected");
                }
                nap();
            }
            if (closed || transport.state() == Transport.State.CLOSED || System.nanoTime() >= until) {
                throw new IOException("Disc transfer timed out");
            }
            if (size == 0) {
                return;
            }
            offset += size;
        }
    }

    public void data(UUID peer, byte[] bytes) {
        if (source != null || closed || !peer.equals(transport.hostId()) || bytes.length < HEADER) {
            return;
        }
        synchronized (reception) {
            ByteBuffer message = ByteBuffer.wrap(bytes);
            if (!ours(message)) {
                return;
            }
            int id = message.getInt(), index = message.getInt(), total = message.getInt(), offset = message.getInt();
            double length = message.getDouble();
            Pending request = pending;
            if (request == null || request.id != id || request.index != index || request.result.isDone()) {
                return;
            }
            if (!Double.isFinite(length) || length <= 0 || length > DiscSource.MAX_DURATION || total < 0
                    || total > DiscSource.MAX_SEGMENT_BYTES || offset != request.offset
                    || message.remaining() > total - offset || duration != 0 && duration != length
                    || index == -1 && total != 0 || index >= 0 && total == 0) {
                request.result.completeExceptionally(new IOException("The host could not provide a valid disc segment"));
                return;
            }
            if (request.bytes == null) {
                request.bytes = new byte[total];
            }
            if (request.bytes.length != total) {
                request.result.completeExceptionally(new IOException("The disc segment changed during transfer"));
                return;
            }
            int size = message.remaining();
            message.get(request.bytes, offset, size);
            request.offset += size;
            duration = length;
            if (request.offset == total) {
                request.result.complete(request.bytes);
            }
        }
    }

    private static void nap() throws IOException {
        try {
            Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Disc transfer was cancelled");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        http.stop(0);
        encoder.shutdownNow();
        workers.shutdownNow();
        DiscRun current = run;
        if (current != null) {
            current.close();
        }
        synchronized (reception) {
            if (pending != null) {
                pending.result.completeExceptionally(new IOException("Disc session closed"));
            }
        }
        synchronized (this) {
            segments.values().forEach(f -> f.ready.completeExceptionally(new IOException("Disc session closed")));
            segments.clear();
            work.clear();
            cachedBytes = 0;
        }
    }

    private static final class Pending {
        final int id, index;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        byte[] bytes;
        int offset;

        Pending(int id, int index) {
            this.id = id;
            this.index = index;
        }
    }

    private record Cached(DiscSource.Quality quality, CompletableFuture<byte[]> ready, Runnable work) {}

    private record Delivery(int id, int index, int bytes, long started) {}

    private record Request(int id, int index) {}

    /**
     * Holds the quality down while this computer encodes segments slower than they play, and lets it back up
     * gradually; how fast each viewer's connection is, the Transport measures (see LinkRate).
     */
    static final class Adaptive {
        private long limit = Long.MAX_VALUE;
        private long recoverAfter;

        synchronized long budget(long measured) {
            return Math.min(measured, limit);
        }

        synchronized void encoded(long nanos, long budget) {
            encoded(nanos, budget, System.nanoTime());
        }

        synchronized void encoded(long nanos, long budget, long now) {
            if (nanos > 3_500_000_000L) {
                limit = Math.min(limit, Math.max(500_000, (long) (budget * 3e9 / nanos)));
                recoverAfter = now + 45_000_000_000L;
            } else if (limit != Long.MAX_VALUE && now - recoverAfter >= 0) {
                limit = Math.min(20_000_000, limit + Math.max(250_000, limit / 10));
                recoverAfter = now + 15_000_000_000L;
            }
        }
    }
}
