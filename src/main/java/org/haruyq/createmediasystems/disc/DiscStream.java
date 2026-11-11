package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

import org.haruyq.createmediasystems.p2p.P2pOverlay;
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
    /** As host: the request each viewer waits for now. Its next is in place of it: it waits for one at a time. */
    private final Map<UUID, Request> waiting = new ConcurrentHashMap<>();
    private final Set<UUID> serving = new HashSet<>();
    /** The segment this computer's player asked for last, or opens at: the three after it are read ahead (see wanted). */
    private volatile int playing = -1;
    /** How many segments in a row a DVD's pass took longer to read than they play; -1 once that is said. */
    private int slow;
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
        /** What the P2P overlay shows of it, as host or viewer, as of {@code now}: nothing on the other side. */
        public List<P2pOverlay.Line> lines(boolean hosting, long now) {
            List<P2pOverlay.Line> lines = new ArrayList<>();
            if (hosting) {
                if (host && encoder != null) {
                    lines.add(new P2pOverlay.Line("Disc: " + encoder + (encoded < 0 ? ""
                            : ", segment " + encoded + " at " + quality), P2pOverlay.GRAY));
                }
                return lines;
            }
            if (host) {
                return lines;
            }
            if (waitingSince != 0) {
                long waited = now - waitingSince;
                // A segment plays for SEGMENT_SECONDS: waiting longer than that, playback has to stall.
                int color = waited > DiscSource.SEGMENT_SECONDS * P2pOverlay.SECOND ? P2pOverlay.RED
                        : waited > P2pOverlay.SECOND * 2 ? P2pOverlay.YELLOW : P2pOverlay.WHITE;
                lines.add(new P2pOverlay.Line("Disc: waiting for " + (waiting < 0 ? "the length" : "segment " + waiting)
                        + " for " + P2pOverlay.seconds(waited), color));
            } else {
                lines.add(new P2pOverlay.Line("Disc: idle", P2pOverlay.GRAY));
            }
            if (last >= 0) {
                lines.add(new P2pOverlay.Line("Last segment " + last + ": " + P2pOverlay.bytes(lastBytes) + " in "
                        + P2pOverlay.seconds(lastNanos) + " (" + P2pOverlay.mbps((long) (lastBytes * 8e9 / Math.max(1, lastNanos)))
                        + ")", P2pOverlay.WHITE));
            }
            if (failures > 0) {
                lines.add(new P2pOverlay.Line("Failed requests: " + failures + (error == null ? "" : ", last: " + error),
                        P2pOverlay.RED));
            }
            return lines;
        }
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
        playing = start - 1;
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
                playing = index;
                sweep();
                body = await(segment(index));
                // Ahead only of a player going on from the segment before: each opens at the top first, wherever it
                // then starts, and a DVD's pass sent on from there seeks away from where the disc plays. And only
                // where it still is: it may have sought on while this waited.
                for (int i = index + 1; playing == index && known(index - 1) && i < Math.min(count(), index + 4); i++) {
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
            if (!closed && !(failed instanceof Stale)) {
                status.accept("Disc buffering failed: " + failed.getMessage());
            }
        } finally {
            httpSlots.release();
        }
    }

    private synchronized boolean known(int index) {
        return segments.containsKey(index);
    }

    /** A segment nobody waits for any more (see sweep). */
    static final class Stale extends IOException {
        Stale() {
            super("Nobody waits for this disc segment any more");
        }
    }

    /**
     * Whether {@code index} is where a player at {@code playing} is: the segment it asked for, one of the three read
     * ahead of it, or the top, which every player opens at.
     */
    static boolean follows(int index, int playing) {
        return index == 0 || index >= playing && index <= playing + 3;
    }

    /** Whether anyone waits for {@code index} now: this computer's player (see follows), or a viewer. */
    private boolean wanted(int index) {
        return follows(index, playing) || waiting.values().stream().anyMatch(asked -> asked.index == index);
    }

    /** Drop abandoned reads after a seek, before they can restart the DVD pass at an old position. */
    private synchronized void sweep() {
        for (var it = segments.entrySet().iterator(); it.hasNext();) {
            var each = it.next();
            Cached queued = each.getValue();
            if (!queued.ready.isDone() && !wanted(each.getKey()) && work.remove(queued.work)) {
                it.remove();
                queued.ready.completeExceptionally(new Stale());
            }
        }
        synchronized (reception) {
            if (pending != null && pending.index >= 0 && !wanted(pending.index)) {
                pending.result.completeExceptionally(new Stale());
            }
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
        DiscSource.Quality quality = source == null ? null : source.quality(available);
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
                if (result.isDone()) {
                    // A DVD's pass kept it on its way to a later one (see encode).
                    return;
                }
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
                    DiscRun before = run;
                    try {
                        bytes = encode(index, quality);
                    } catch (DiscSource.EncoderFailed failed) {
                        // Only the encoder's own failure: a disc that reads slowly or not at all does no better
                        // on the CPU, and came back from here encoded there for the rest of the disc.
                        if (codec.equals("libx264") || Thread.currentThread().isInterrupted()) {
                            throw failed;
                        }
                        codec = "libx264";
                        status.accept("Hardware encoding failed; using libx264 for this disc");
                        bytes = encode(index, quality);
                    }
                    // A DVD's pass seeks as it starts and waits on its drive: seconds that say nothing of how fast
                    // this encodes, and a lower quality for them would only start the pass over.
                    if (source.segmented()) {
                        adaptive.encoded(System.nanoTime() - started, available);
                    }
                    DiscRun current = run;
                    long took = System.nanoTime() - started;
                    report(index, source.segmented() || current == null ? quality : current.quality, took);
                    slow(current != null && current == before && took > DiscSource.SEGMENT_SECONDS * 1_000_000_000L, took);
                }
                keep(index, bytes, result);
            } catch (IOException | RuntimeException failed) {
                if (source != null && !(failed instanceof Stale)) {
                    // What FFmpeg said goes no further than this log: it names this computer's drive and paths.
                    status.accept("Disc segment " + index + " failed: " + failed.getMessage()
                            + (failed.getCause() == null ? "" : " (FFmpeg: " + failed.getCause().getMessage() + ")"));
                }
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

    /** {@code index} is encoded, in {@code nanos} (-1: on a DVD's pass's way to another). */
    private void report(int index, DiscSource.Quality used, long nanos) {
        // A CD has no picture to say the quality of.
        boolean sound = source.cd() != null;
        lastQuality = sound ? "192 kbps" : used.height() + "p" + used.fps() + " " + used.kbps() + " kbps";
        lastEncoded = index;
        status.accept("Disc segment " + index + ": " + (sound ? lastQuality
                : used.height() + "p " + used.fps() + " fps, " + used.kbps() + " kbps")
                + (nanos < 0 ? "" : String.format(Locale.ROOT, " in %.1f s", nanos / 1e9)));
    }

    private void slow(boolean slower, long nanos) {
        if (slow < 0 || !slower) {
            slow = Math.min(slow, 0);
        } else if (++slow == 3) {
            slow = -1;
            status.accept(String.format(Locale.ROOT, "DVD reading and encoding took %.1f s for %d s of video; "
                    + "playback is waiting for the host. Check the drive and FFmpeg runtime.", nanos / 1e9, DiscSource.SEGMENT_SECONDS));
        }
    }

    private synchronized void keep(int index, byte[] bytes, CompletableFuture<byte[]> result) throws IOException {
        if (closed) {
            throw new IOException("Disc stream was closed");
        }
        if (!result.complete(bytes)) {
            return;
        }
        cachedBytes += bytes.length;
        var it = segments.entrySet().iterator();
        while ((cachedBytes > CACHE_BYTES || segments.size() > 16) && it.hasNext()) {
            var old = it.next();
            // The top stays: every player opens there first (see prepare), and a DVD's pass would seek back for it.
            if (old.getValue().ready.isDone() && !old.getValue().ready.isCompletedExceptionally()
                    && old.getKey() != index && old.getKey() != 0) {
                cachedBytes -= old.getValue().ready.getNow(new byte[0]).length;
                it.remove();
            }
        }
    }

    /** A segment a DVD's pass handed over on its way to a later one: whoever asks for it next has it. */
    private synchronized void passed(int index, byte[] bytes, DiscSource.Quality quality) throws IOException {
        Cached known = segments.get(index);
        if (known == null) {
            known = new Cached(quality, new CompletableFuture<>(), () -> { });
            segments.put(index, known);
        } else {
            work.remove(known.work);
        }
        keep(index, bytes, known.ready);
        report(index, quality, -1);
    }

    /**
     * On the encoder thread. A DVD's segment comes from its running pass (see DiscSource#run) wherever that gets to
     * it: its next, or one a little on (see DiscRun#ahead), keeping those on the way. Segments are asked for out of
     * order (41, 43, 42: one that failed asked again, a player seeking on to catch up, two players), and a pass
     * started over for each sought every time, for seconds on a real drive.
     */
    private byte[] encode(int index, DiscSource.Quality quality) throws IOException {
        if (source.segmented()) {
            return source.encode(index, quality, codec);
        }
        DiscRun current = run;
        int ahead = current == null ? -1 : current.ahead(index, quality, codec);
        if (ahead < 0) {
            if (current != null) {
                current.close();
            }
            status.accept("Disc pass from segment " + index
                    + (current == null ? "" : ", in place of one at " + current.next()));
            run = current = source.run(index, quality, codec);
            if (closed) {
                current.close();
                throw new IOException("Disc stream was closed");
            }
            ahead = 0;
        }
        try {
            for (; ahead > 0; ahead--) {
                passed(index - ahead, current.take(), current.quality);
            }
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
            if (!(failed instanceof Stale)) {
                failures++;
                lastError = failed.getMessage();
            }
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
                if (closed || serving.size() >= 16 && !serving.contains(peer)) {
                    return;
                }
                Request asked = new Request(request, index, new CompletableFuture<>());
                Request previous = waiting.put(peer, asked);
                if (previous != null) previous.cancelled.completeExceptionally(new Stale());
                sweep();
                if (serving.add(peer)) workers.execute(() -> servePeer(peer));
            }
        }
    }

    private void servePeer(UUID peer) {
        while (!closed) {
            Request asked;
            synchronized (this) {
                asked = waiting.get(peer);
                if (asked == null) {
                    serving.remove(peer);
                    return;
                }
            }
            try {
                byte[] data = new byte[0];
                if (asked.index >= 0) {
                    await(requested(peer, asked));
                    data = await(requested(peer, asked));
                }
                if (waiting.get(peer) == asked) {
                    deliveries.put(peer, new Delivery(asked.id, asked.index, data.length, System.nanoTime()));
                    reply(peer, asked, data);
                }
            } catch (IOException failed) {
                if (waiting.get(peer) == asked && !closed) {
                    status.accept("Disc segment " + asked.index + " failed for a viewer: " + failed.getMessage());
                    try {
                        reply(peer, asked, null);
                    } catch (IOException disconnected) {
                    }
                }
            } finally {
                waiting.remove(peer, asked);
            }
        }
    }

    private synchronized CompletableFuture<byte[]> requested(UUID peer, Request asked) {
        if (waiting.get(peer) != asked) return CompletableFuture.failedFuture(new Stale());
        return segment(asked.index, false, true).applyToEither(asked.cancelled, bytes -> bytes);
    }

    private boolean ours(ByteBuffer message) {
        return message.getInt() == MAGIC && message.getLong() == sourceId.getMostSignificantBits()
                && message.getLong() == sourceId.getLeastSignificantBits();
    }

    /** Sends {@code peer} the segment it {@code asked} for (null: none), for as long as it still waits for it. */
    private void reply(UUID peer, Request asked, byte[] bytes) throws IOException {
        int request = asked.id, index = asked.index;
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
                if (waiting.get(peer) != asked) {
                    return;
                }
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
            waiting.clear();
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

    private record Request(int id, int index, CompletableFuture<byte[]> cancelled) {}

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
