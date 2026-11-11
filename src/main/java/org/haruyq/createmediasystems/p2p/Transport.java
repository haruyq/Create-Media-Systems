package org.haruyq.createmediasystems.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.haruyq.createmediasystems.ApiHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCCandidatePairChangeEvent;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

/**
 * A client's direct P2P session (see P2p): signals through the API's WebSocket with its ticket, then holds a WebRTC
 * DataChannel to each viewer (as host) or to the host (as viewer), over direct ICE candidates only: no TURN, no relay.
 * The host measures each viewer's connection as it opens, by sending as much as it takes for a moment (see
 * LinkRate), then sends payloads to each paced at what it measured. Client only: it loads native WebRTC.
 * <p>
 * Everything runs on the session's own thread; native callbacks only hand work to it, and nothing runs once the
 * session is closed. Listener calls come on that thread too, never the game's.
 */
public final class Transport implements AutoCloseable {
    /** Largest payload {@link #send} takes. */
    public static final int MAX_PAYLOAD = 64 * 1024;
    /** Most bytes waiting for one peer, here and in WebRTC's own buffer each. */
    static final int MAX_QUEUED = 1 << 20;
    /** Most received bytes waiting for the listener, each frame counted {@link #FRAME_COST} more. */
    static final long MAX_INBOUND = 4L << 20;
    static final int FRAME_COST = 256;
    private static final int MAX_SIGNAL = 64 * 1024;
    private static final int MAX_SDP = 48 * 1024;
    private static final int MAX_CANDIDATE = 2048;
    private static final int MAX_PENDING = 64;
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long TICK_MILLIS = 10;
    private static final byte DATA = 0, PING = 1, PONG = 2, CONTROL = 3, PROBE = 4, PROBE_REPORT = 5;
    /** How long the host sends a new viewer probe frames, and in what size, keeping at most this much in flight. */
    private static final long PROBE_NANOS = 1_000_000_000L;
    private static final int PROBE_FRAME = 16 * 1024;
    private static final int PROBE_BUFFER = 512 * 1024;
    private static final Pattern CANDIDATE_TYPE = Pattern.compile(" typ (\\S+)");
    private static final String LIBRARY = "webrtc-java-windows-x86_64.dll";
    private static final Logger LOGGER = LoggerFactory.getLogger(Transport.class);

    public enum State {
        CONNECTING, CONNECTED, CLOSED
    }

    public interface Listener {
        default void control(UUID from, byte[] payload) {}
        /** {@code payload} (the receiver's own copy) from the host. */
        default void data(UUID from, byte[] payload) {
        }

        /** Something worth telling the player: a peer connected or failed, a ping's time, the session closing. */
        void status(String message);
    }

    private final Protocol.Ticket ticket;
    private final Listener listener;
    private final Consumer<String> signal;
    private final Runnable onClose;
    private final ScheduledExecutorService thread;
    final AtomicLong inbound = new AtomicLong();
    final Map<UUID, Link> links = new LinkedHashMap<>();
    /** Viewers the host has tried, so one whose connection failed isn't tried again until it rejoins. */
    private final Set<UUID> tried = new HashSet<>();
    /** Channels WebRTC handed over that this session's thread has yet to take or release. */
    private final Set<RTCDataChannel> handed = ConcurrentHashMap.newKeySet();
    private PeerConnectionFactory factory;
    private HeadlessAudioDeviceModule audio;
    private volatile State state = State.CONNECTING;
    private volatile long budgetBps;
    private volatile long received;
    private volatile long backlog;

    /**
     * A session signalling with {@code signal}, which delivers the API's messages to {@link #signal(String)}, and
     * running {@code onClose} once it closes, however early.
     */
    Transport(Protocol.Ticket ticket, Listener listener, Consumer<String> signal, Runnable onClose) {
        this.ticket = ticket;
        this.listener = listener;
        this.signal = signal;
        this.onClose = onClose;
        this.thread = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("CMS P2P").factory());
        post(() -> {
            audio = new HeadlessAudioDeviceModule();
            factory = new PeerConnectionFactory(audio);
        });
        thread.scheduleAtFixedRate(() -> run(this::tick), TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Connects to the API's signaling at {@code apiBase} with {@code ticket}, which goes in a header, never the URL.
     * Natives must be loaded ({@link #loadNatives}).
     */
    public static Transport open(String apiBase, Protocol.Ticket ticket, Listener listener) {
        URI uri = Protocol.endpoint(apiBase, "p2p/sessions/" + ticket.sessionId() + "/signal", true);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Socket socket = new Socket(http, uri, ticket.token());
        Transport transport = new Transport(ticket, listener, socket::send, socket::close);
        socket.signal = transport::signal;
        socket.fail = transport::fail;
        socket.connect();
        return transport;
    }

    /**
     * Unpacks the bundled native WebRTC into {@code dir} (unless it already is) for webrtc-java to load from there,
     * or into another folder if this Java can't pass {@code dir}'s path to Windows (see {@link #loadable}). Call
     * before the first session; never on a dedicated server.
     */
    public static synchronized void loadNatives(Path dir) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IOException("Direct P2P comes with native WebRTC for 64-bit Windows only so far");
        }
        String version;
        try (InputStream in = resource("version")) {
            version = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        Charset ansi;
        try {
            ansi = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
        } catch (RuntimeException unknown) {
            ansi = StandardCharsets.US_ASCII;
        }
        List<Path> fallbacks = new ArrayList<>();
        for (String variable : List.of("LOCALAPPDATA", "ProgramData")) {
            String base = System.getenv(variable);
            if (base != null && !base.isBlank()) {
                fallbacks.add(Path.of(base, "create_media_systems", "webrtc"));
            }
        }
        fallbacks.add(Path.of(System.getProperty("java.io.tmpdir"), "create_media_systems", "webrtc"));
        IOException failed = null;
        for (Path base : loadable(dir, ansi, fallbacks)) {
            Path target = base.resolve(version);
            try {
                unpack(target);
            } catch (IOException e) {
                failed = e;
                continue;
            }
            // webrtc-java's NativeLoader.LIBRARY_DIR_PROPERTY: loads it there, in place.
            System.setProperty("webrtc.java.library.dir", target.toAbsolutePath().toString());
            return;
        }
        throw failed != null ? failed : new IOException("This Java can't pass any folder's path to Windows for native WebRTC: "
                + "rename the game folder in Latin letters, or set Windows' system locale (language for non-Unicode programs) to match it");
    }

    /**
     * Of {@code preferred} and then {@code fallbacks}, those whose path this Java can pass to Windows. HotSpot hands
     * Windows a library's path in the system's code page ({@code ansi}, sun.jnu.encoding), so a folder named in a
     * script that code page lacks turns to "?" and the library fails to load ("Can't find dependent libraries"):
     * an instance folder named in Japanese, on a PC whose system locale is English.
     */
    static List<Path> loadable(Path preferred, Charset ansi, List<Path> fallbacks) {
        CharsetEncoder encoder = ansi.newEncoder();
        List<Path> loadable = new ArrayList<>();
        for (Path path : java.util.stream.Stream.concat(java.util.stream.Stream.of(preferred), fallbacks.stream()).toList()) {
            if (encoder.canEncode(path.toAbsolutePath().toString())) {
                loadable.add(path);
            }
        }
        return loadable;
    }

    private static void unpack(Path target) throws IOException {
        Path library = target.resolve(LIBRARY);
        if (Files.isRegularFile(library)) {
            return;
        }
        Files.createDirectories(target);
        Path partial = Files.createTempFile(target, LIBRARY, ".part");
        try (InputStream in = resource(LIBRARY)) {
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, library, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = Transport.class.getResourceAsStream("/create_media_systems/webrtc/" + name);
        if (in == null) {
            throw new IOException("This Create: Media Systems jar has no bundled WebRTC");
        }
        return in;
    }

    public State state() {
        return state;
    }

    public boolean host() {
        return ticket.host();
    }

    public UUID hostId() {
        return ticket.hostId();
    }

    /**
     * In bit/s: as host, the slowest connected viewer's pace (see LinkRate), 0 while none is connected; as viewer,
     * what the host's probe measured coming in, 0 until it has.
     */
    public long budgetBps() {
        return budgetBps;
    }

    /**
     * Most bytes waiting for one connected viewer, in its queue here and in WebRTC's buffer, as of the last 10 ms;
     * 0 if none. It grows while payloads come faster than the budget or the network lets them out.
     */
    public long backlog() {
        return backlog;
    }

    public long received() {
        return received;
    }

    /**
     * One connection as of now, for the debug overlay: its ICE route, the last round trip measured on it (0 before
     * any), its pace (host) or what the probe measured (viewer), whether that probe has answered, the bytes waiting
     * to go out, and since when it is open.
     */
    public record LinkInfo(UUID peer, boolean open, String route, long rttNanos, long bps, boolean measured, long backlog,
                           long openedAt) {
    }

    public synchronized List<LinkInfo> links() {
        return links.values().stream().map(l -> new LinkInfo(l.peer, l.open, l.route, l.rttNanos,
                ticket.host() ? l.rate.bps() : budgetBps, ticket.host() ? l.rate.measured() : l.probeEnded, l.backlog,
                l.openedAt)).toList();
    }

    public synchronized List<UUID> connected() {
        return links.values().stream().filter(l -> l.open).map(l -> l.peer).toList();
    }

    /**
     * Queues a copy of {@code payload} (at most {@link #MAX_PAYLOAD} bytes) for every connected viewer, sent as the
     * budget allows. False, and queued for none, if this isn't the host, no viewer is connected or one has a full
     * queue: try again later.
     */
    public synchronized boolean send(byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Payload over " + MAX_PAYLOAD + " bytes");
        }
        List<Link> open = links.values().stream().filter(l -> l.open).toList();
        if (state == State.CLOSED || !ticket.host() || open.isEmpty()
                || open.stream().anyMatch(l -> l.queued + payload.length + 1 > MAX_QUEUED)) {
            return false;
        }
        byte[] frame = frame(DATA, payload);
        for (Link link : open) {
            link.queue.add(frame);
            link.queued += frame.length;
        }
        return true;
    }

    public synchronized boolean send(UUID peer, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Payload too large");
        }
        return ticket.host() && queue(peer, DATA, payload);
    }

    public synchronized boolean control(UUID peer, byte[] payload) {
        if (payload.length > 256) {
            throw new IllegalArgumentException("Control message too large");
        }
        return queue(peer, CONTROL, payload);
    }

    private boolean queue(UUID peer, byte type, byte[] payload) {
        Link link = links.get(peer);
        if (state == State.CLOSED || link == null || !link.open || link.queued + payload.length + 1 > MAX_QUEUED) {
            return false;
        }
        byte[] frame = frame(type, payload);
        link.queue.add(frame);
        link.queued += frame.length;
        return true;
    }

    /** Pings every connected peer over its DataChannel; each answer is reported with its time. */
    public void ping() {
        post(() -> {
            if (links.values().stream().noneMatch(l -> l.open)) {
                listener.status("No peer is connected directly yet.");
            }
            ping(false);
        });
    }

    /** Pings every connected peer without reporting: the answers only update {@link LinkInfo#rttNanos}. */
    public void measure() {
        post(() -> ping(true));
    }

    /** A quiet ping carries a ninth byte, which the peer echoes back with the rest. */
    private void ping(boolean quiet) {
        for (Link link : links.values()) {
            if (link.open && link.channel.getBufferedAmount() < MAX_QUEUED) {
                ByteBuffer body = ByteBuffer.allocate(quiet ? 9 : 8).putLong(System.nanoTime());
                link.sendNow(frame(PING, body.array()));
            }
        }
    }

    @Override
    public void close() {
        post(() -> shutDown(null));
    }

    void fail(String reason) {
        post(() -> shutDown(reason));
    }

    void signal(String text) {
        post(() -> {
            JsonObject message;
            try {
                message = JsonParser.parseString(text).getAsJsonObject();
                handle(message);
            } catch (RuntimeException e) {
                shutDown("The API sent a signaling message this client does not understand.");
            }
        });
    }

    private void handle(JsonObject message) {
        String type = message.get("type").getAsString();
        switch (type) {
            case "peers" -> {
                if (!UUID.fromString(message.get("host_id").getAsString()).equals(ticket.hostId())) {
                    throw new IllegalArgumentException();
                }
                state = State.CONNECTED;
                if (ticket.host()) {
                    Set<UUID> viewers = new HashSet<>();
                    for (JsonElement peer : message.getAsJsonArray("peers")) {
                        JsonObject p = peer.getAsJsonObject();
                        UUID id = UUID.fromString(p.get("peer_id").getAsString());
                        if ("viewer".equals(p.get("role").getAsString()) && !id.equals(ticket.peerId())) {
                            viewers.add(id);
                        }
                    }
                    for (UUID gone : List.copyOf(links.keySet())) {
                        if (!viewers.contains(gone)) {
                            drop(links.get(gone), null);
                        }
                    }
                    tried.retainAll(viewers);
                    for (UUID viewer : viewers) {
                        if (tried.add(viewer)) {
                            offer(viewer);
                        }
                    }
                }
            }
            case "offer" -> {
                UUID from = UUID.fromString(message.get("from").getAsString());
                if (ticket.host() || !from.equals(ticket.hostId())) {
                    return;
                }
                Link old = links.remove(from);
                if (old != null) {
                    old.close();
                }
                Link link = new Link(from);
                link.describe(new RTCSessionDescription(RTCSdpType.OFFER, sdp(message)), () ->
                        link.pc.createAnswer(new RTCAnswerOptions(), link.created("answer")));
            }
            case "answer" -> {
                Link link = links.get(UUID.fromString(message.get("from").getAsString()));
                if (!ticket.host() || link == null || link.remoteSet) {
                    return;
                }
                link.describe(new RTCSessionDescription(RTCSdpType.ANSWER, sdp(message)), () -> { });
            }
            case "ice" -> {
                Link link = links.get(UUID.fromString(message.get("from").getAsString()));
                String candidate = message.get("candidate").getAsString();
                if (link == null || candidate.length() > MAX_CANDIDATE || !direct(candidate)) {
                    return;
                }
                RTCIceCandidate ice = new RTCIceCandidate(message.get("sdp_mid").getAsString(),
                        message.get("sdp_mline_index").getAsInt(), candidate);
                if (link.remoteSet) {
                    link.pc.addIceCandidate(ice);
                } else if (link.pending.size() < MAX_PENDING) {
                    link.pending.add(ice);
                }
            }
            case "error" -> listener.status("Signaling: " + message.get("message").getAsString());
            case "closed" -> shutDown("The session closed: " + message.get("message").getAsString());
            default -> {
            }
        }
    }

    private static String sdp(JsonObject message) {
        String sdp = message.get("sdp").getAsString();
        return sdp.length() > MAX_SDP ? "" : sdp;
    }

    /** Whether {@code candidate} is a direct one (host, server or peer reflexive), never a relay. */
    static boolean direct(String candidate) {
        Matcher type = CANDIDATE_TYPE.matcher(candidate);
        return type.find() && Set.of("host", "srflx", "prflx").contains(type.group(1));
    }

    private void offer(UUID viewer) {
        Link link = new Link(viewer);
        RTCDataChannelInit init = new RTCDataChannelInit();
        link.attach(link.pc.createDataChannel("cms", init));
        link.pc.createOffer(new RTCOfferOptions(), link.created("offer"));
    }

    /**
     * The bytes {@code peer} acknowledged, {@code nanos} after they were queued for it: how its pace follows the
     * connection (see LinkRate). Host only; deliveries that began before its probe answered say nothing.
     */
    public void delivered(UUID peer, long bytes, long nanos) {
        post(() -> {
            Link link = links.get(peer);
            if (ticket.host() && link != null && link.open && link.rate.measured()
                    && System.nanoTime() - nanos - link.probedAt >= 0) {
                link.rate.delivered(bytes, nanos);
            }
        });
    }

    private void tick() {
        long now = System.nanoTime();
        long most = 0;
        long slowest = 0;
        for (Link link : List.copyOf(links.values())) {
            if (!link.open) {
                if (now - link.createdAt > CONNECT_TIMEOUT_NANOS) {
                    drop(link, "Could not connect directly to " + name(link.peer) + " in 30 s. Both of you may be "
                            + "behind strict NATs or firewalls: try the same LAN, allowing UDP or forwarding a port.");
                }
                continue;
            }
            if (ticket.host() && !probe(link, now)) {
                continue;
            }
            slowest = slowest == 0 ? link.rate.bps() : Math.min(slowest, link.rate.bps());
            double rate = link.rate.bps() / 8.0;
            link.tokens = Math.min(Math.max(MAX_PAYLOAD + 1, rate * 0.05),
                    link.tokens + rate * (now - link.lastTick) / 1e9);
            link.lastTick = now;
            while (!link.queue.isEmpty() && link.queue.peek().length <= link.tokens
                    && link.channel.getBufferedAmount() < MAX_QUEUED) {
                byte[] frame = link.queue.poll();
                link.queued -= frame.length;
                link.tokens -= frame.length;
                if (!link.sendNow(frame)) {
                    break;
                }
            }
            if (!link.closed) {
                link.backlog = link.queued + link.channel.getBufferedAmount();
                most = Math.max(most, link.backlog);
            }
        }
        backlog = most;
        if (ticket.host()) {
            budgetBps = slowest;
        }
    }

    private boolean probe(Link link, long now) {
        if (link.probeEnded) {
            return true;
        }
        if (now - link.openedAt < PROBE_NANOS) {
            byte[] frame = new byte[PROBE_FRAME + 1];
            frame[0] = PROBE;
            while (link.channel.getBufferedAmount() < PROBE_BUFFER && link.sendNow(frame)) {
                // Filled up to the bound, or dropped.
            }
            return false;
        }
        link.probeEnded = link.sendNow(new byte[] {PROBE, 1});
        return false;
    }

    private static byte[] frame(byte type, byte[] body) {
        byte[] frame = new byte[body.length + 1];
        frame[0] = type;
        System.arraycopy(body, 0, frame, 1, body.length);
        return frame;
    }

    private void receive(Link link, byte[] frame) {
        if (frame.length == 0) {
            return;
        }
        switch (frame[0]) {
            case CONTROL -> {
                if (frame.length <= 257) {
                    listener.control(link.peer, java.util.Arrays.copyOfRange(frame, 1, frame.length));
                }
            }
            case DATA -> {
                if (!ticket.host() && link.peer.equals(ticket.hostId())) {
                    byte[] payload = new byte[frame.length - 1];
                    System.arraycopy(frame, 1, payload, 0, payload.length);
                    received += payload.length;
                    listener.data(link.peer, payload);
                }
            }
            case PROBE -> {
                if (!ticket.host() && link.peer.equals(ticket.hostId()) && !link.probeEnded) {
                    probed(link, frame);
                }
            }
            case PROBE_REPORT -> {
                if (ticket.host() && link.probeEnded && !link.rate.measured() && frame.length == 17) {
                    ByteBuffer report = ByteBuffer.wrap(frame, 1, 16);
                    link.rate.probed(report.getLong(), report.getLong());
                    link.probedAt = System.nanoTime();
                    LOGGER.info(String.format(Locale.ROOT, "Measured the direct connection to %s: %.1f Mbps",
                            name(link.peer), link.rate.bps() / 1e6));
                }
            }
            case PING -> {
                if (link.channel.getBufferedAmount() < MAX_QUEUED) {
                    frame[0] = PONG;
                    link.sendNow(frame);
                }
            }
            case PONG -> {
                long rtt = frame.length == 9 || frame.length == 10 ? System.nanoTime() - ByteBuffer.wrap(frame, 1, 8).getLong() : -1;
                // A round trip under a minute; the peer echoes the time it was sent, so anything else is not one.
                if (rtt >= 0 && rtt < 60_000_000_000L) {
                    link.rttNanos = rtt;
                    if (frame.length == 9) {
                        listener.status(String.format(Locale.ROOT, "%s: %.1f ms over a direct %s connection", name(link.peer),
                                rtt / 1e6, link.route));
                    }
                }
            }
            default -> { }
        }
    }

    /**
     * A viewer counts the host's probe as it comes: bytes after the first frame, over the time from it to the last.
     * At the end it reports them, and keeps the rate as its own measure (see {@link #budgetBps}).
     */
    private void probed(Link link, byte[] frame) {
        long now = System.nanoTime();
        if (frame.length > 2) {
            if (link.probeFirst == 0) {
                link.probeFirst = now;
            } else {
                link.probeBytes += frame.length;
            }
            link.probeLast = now;
            return;
        }
        link.probeEnded = true;
        long nanos = link.probeLast - link.probeFirst;
        link.sendNow(frame(PROBE_REPORT, ByteBuffer.allocate(16).putLong(link.probeBytes).putLong(nanos).array()));
        LinkRate rate = new LinkRate();
        rate.probed(link.probeBytes, nanos);
        budgetBps = rate.bps();
        LOGGER.info(String.format(Locale.ROOT, "Measured the direct connection from the host: %.1f Mbps", budgetBps / 1e6));
    }

    /** Closes {@code link}, telling the player {@code reason} if any. A viewer has nothing left without its host. */
    private void drop(Link link, String reason) {
        if (link == null || link.closed) {
            return;
        }
        link.close();
        links.remove(link.peer, link);
        if (!ticket.host()) {
            shutDown(reason != null ? reason : "Lost the direct connection to the host.");
        } else if (reason != null) {
            listener.status(reason);
        }
    }

    private void shutDown(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        backlog = 0;
        try {
            for (Link link : List.copyOf(links.values())) {
                link.close();
            }
            links.clear();
            // Every connection is closed, so WebRTC hands over no more: release what is left while the factory lives.
            for (RTCDataChannel channel : handed) {
                release(channel::close);
                release(channel::dispose);
            }
            handed.clear();
            if (factory != null) {
                release(factory::dispose);
            }
            if (audio != null) {
                release(audio::dispose);
            }
        } finally {
            thread.shutdown();
            onClose.run();
        }
        listener.status(reason != null ? reason : "Left the P2P session.");
    }

    private static void release(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not release native WebRTC", e);
        }
    }

    /** Runs {@code task} on the session's thread, unless the session is closed. */
    private void post(Runnable task) {
        post(task, () -> { });
    }

    /** {@link #post(Runnable)}, then {@code after} either way, once: on the session's thread or, if closed, here. */
    private void post(Runnable task, Runnable after) {
        try {
            thread.execute(() -> {
                try {
                    run(task);
                } finally {
                    after.run();
                }
            });
        } catch (RejectedExecutionException e) {
            after.run();
        }
    }

    private synchronized void run(Runnable task) {
        if (state != State.CLOSED) {
            try {
                task.run();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("Direct P2P failed", e);
                shutDown("Direct P2P failed: " + ApiHttp.reason(e));
            }
        }
    }

    private void send(JsonObject message) {
        String text = message.toString();
        if (text.length() > MAX_SIGNAL) {
            shutDown("A signaling message came out too large.");
            return;
        }
        signal.accept(text);
    }

    private String name(UUID peer) {
        return peer.equals(ticket.hostId()) ? "the host" : "viewer " + peer;
    }

    final class Link {
        final UUID peer;
        final RTCPeerConnection pc;
        final long createdAt = System.nanoTime();
        final List<RTCIceCandidate> pending = new ArrayList<>();
        /** This side's candidates, held until its offer or answer is signalled, which they must not overtake. */
        final List<JsonObject> held = new ArrayList<>();
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        RTCDataChannel channel;
        boolean remoteSet, signalled, open, closed;
        /** Set on WebRTC's thread once this peer sent too much: nothing more of it is taken. */
        volatile boolean rejected;
        int queued;
        double tokens;
        long lastTick = createdAt;
        final LinkRate rate = new LinkRate();
        /** When it opened and when its probe answered (host); the probe as it comes in (viewer). */
        long openedAt, probedAt, probeFirst, probeLast, probeBytes;
        /** The last round trip measured, and the bytes waiting for it as of the last tick (see LinkInfo). */
        long rttNanos, backlog;
        boolean probeEnded;
        String route = "direct";

        Link(UUID peer) {
            this.peer = peer;
            RTCConfiguration config = new RTCConfiguration();
            for (String url : ticket.stunUrls()) {
                RTCIceServer server = new RTCIceServer();
                server.urls.add(url);
                config.iceServers.add(server);
            }
            config.portAllocatorConfig.setDisableRelay(true);
            pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    later(() -> {
                        if (candidate.sdp.length() <= MAX_CANDIDATE && candidate.sdpMid != null && direct(candidate.sdp)) {
                            JsonObject ice = message("ice");
                            ice.addProperty("candidate", candidate.sdp);
                            ice.addProperty("sdp_mid", candidate.sdpMid);
                            ice.addProperty("sdp_mline_index", candidate.sdpMLineIndex);
                            if (signalled) {
                                send(ice);
                            } else if (held.size() < MAX_PENDING) {
                                held.add(ice);
                            }
                        }
                    });
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState change) {
                    later(() -> {
                        if (change == RTCPeerConnectionState.FAILED) {
                            drop(Link.this, "The direct connection to " + name(peer) + " failed: no direct route "
                                    + "between you (strict NAT or firewall). Try the same LAN, allowing UDP or forwarding a port.");
                        } else if (change == RTCPeerConnectionState.CLOSED) {
                            drop(Link.this, null);
                        }
                    });
                }

                @Override
                public void onSelectedCandidatePairChanged(RTCCandidatePairChangeEvent event) {
                    String local = event.getLocal().sdp, remote = event.getRemote().sdp;
                    later(() -> {
                        if (!direct(local) || !direct(remote)) {
                            drop(Link.this, "Refused a relayed route to " + name(peer) + ".");
                        } else {
                            route = type(local) + "/" + type(remote);
                        }
                    });
                }

                @Override
                public void onDataChannel(RTCDataChannel dataChannel) {
                    // Held until taken here, or released by shutDown should the session close first.
                    handed.add(dataChannel);
                    post(() -> {
                        handed.remove(dataChannel);
                        if (closed || ticket.host() || channel != null) {
                            release(dataChannel::close);
                            release(dataChannel::dispose);
                        } else {
                            attach(dataChannel);
                            opened();
                        }
                    });
                }
            });
            if (pc == null) {
                throw new IllegalStateException("WebRTC could not create a peer connection");
            }
            links.put(peer, this);
        }

        void attach(RTCDataChannel dataChannel) {
            channel = dataChannel;
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long sentDataSize) {
                }

                @Override
                public void onStateChange() {
                    later(Link.this::opened);
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    take(buffer.data);
                }
            });
        }

        /**
         * Copies a received frame (WebRTC frees it once this returns) for the session's thread, within the inbound
         * bound. A frame too large, or one more than the listener keeps up with, drops the peer, once.
         */
        void take(ByteBuffer data) {
            if (rejected || state == State.CLOSED) {
                return;
            }
            int size = data.remaining();
            int cost = size + FRAME_COST;
            boolean fits = size <= MAX_PAYLOAD + 1;
            if (fits && inbound.addAndGet(cost) > MAX_INBOUND) {
                inbound.addAndGet(-cost);
                fits = false;
            }
            if (!fits) {
                rejected = true;
                later(() -> drop(this, "Dropped " + name(peer) + ": it sent more than this client keeps up with."));
                return;
            }
            byte[] frame = new byte[size];
            data.get(frame);
            post(() -> {
                if (!closed) {
                    receive(this, frame);
                }
            }, () -> inbound.addAndGet(-cost));
        }

        /** Follows the channel's state; a viewer's may be open already when it is handed over. */
        void opened() {
            RTCDataChannelState now = channel.getState();
            if (now == RTCDataChannelState.OPEN && !open) {
                open = true;
                openedAt = System.nanoTime();
                LOGGER.info("Connected directly to {}.", name(peer));
            } else if (now == RTCDataChannelState.CLOSED) {
                drop(this, "The connection to " + name(peer) + " closed.");
            }
        }

        /** Hands {@code task} to the session's thread, to run only if this link is still current there. */
        void later(Runnable task) {
            post(() -> {
                if (!closed) {
                    task.run();
                }
            });
        }

        void describe(RTCSessionDescription description, Runnable then) {
            pc.setRemoteDescription(description, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    later(() -> {
                        remoteSet = true;
                        pending.forEach(pc::addIceCandidate);
                        pending.clear();
                        then.run();
                    });
                }

                @Override
                public void onFailure(String error) {
                    later(() -> drop(Link.this, "Could not use " + name(peer) + "'s session description."));
                }
            });
        }

        CreateSessionDescriptionObserver created(String type) {
            return new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    later(() -> pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            later(() -> {
                                if (description.sdp.length() > MAX_SDP) {
                                    drop(Link.this, "The session description came out too large.");
                                    return;
                                }
                                JsonObject sdp = message(type);
                                sdp.addProperty("sdp", description.sdp);
                                send(sdp);
                                signalled = true;
                                held.forEach(Transport.this::send);
                                held.clear();
                            });
                        }

                        @Override
                        public void onFailure(String error) {
                            later(() -> drop(Link.this, "Could not set up the connection to " + name(peer) + "."));
                        }
                    }));
                }

                @Override
                public void onFailure(String error) {
                    later(() -> drop(Link.this, "Could not set up the connection to " + name(peer) + "."));
                }
            };
        }

        JsonObject message(String type) {
            JsonObject message = new JsonObject();
            message.addProperty("type", type);
            message.addProperty("to", peer.toString());
            return message;
        }

        /** Hands {@code frame} to WebRTC now; false (and the link dropped) if it can't take it. */
        boolean sendNow(byte[] frame) {
            try {
                channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(frame), true));
                return true;
            } catch (Exception e) {
                drop(this, "Could not send to " + name(peer) + ".");
                return false;
            }
        }

        void close() {
            closed = true;
            open = false;
            queue.clear();
            queued = 0;
            if (channel != null) {
                release(channel::unregisterObserver);
                release(channel::close);
                release(channel::dispose);
            }
            release(pc::close);
        }
    }

    private static String type(String candidate) {
        Matcher type = CANDIDATE_TYPE.matcher(candidate);
        return type.find() ? type.group(1) : "?";
    }

    /**
     * The JDK WebSocket to the API's signaling: text in, at most {@link #MAX_SIGNAL} per message, in order out. One
     * lost after it opened is opened again with the same ticket, for as long as the API keeps the ticket's seat: a
     * proxy in front of the API cuts these sockets, while the direct connections they set up carry on without them.
     */
    static final class Socket implements WebSocket.Listener {
        /** The API keeps a lost socket's seat for 120 s: one not back shortly before that is given up. */
        private static final long RETURN_NANOS = TimeUnit.SECONDS.toNanos(110);
        private final HttpClient http;
        private final URI uri;
        private final String token;
        private final StringBuilder partial = new StringBuilder();
        /** What the API sent, and why this gave up: the session's {@link Transport#signal} and {@link Transport#fail}. */
        Consumer<String> signal, fail;
        /** How long after a socket is lost, or could not be opened again, the next is tried. */
        long retryMillis = 2_000;
        private WebSocket ws;
        private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
        private int outstanding;
        private boolean opened, closed;
        private long lostAt;

        Socket(HttpClient http, URI uri, String token) {
            this.http = http;
            this.uri = uri;
            this.token = token;
        }

        synchronized void connect() {
            if (closed) {
                return;
            }
            http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                    .connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, this)
                    .whenComplete((socket, error) -> {
                        if (error != null) {
                            unreachable(error);
                        }
                    });
        }

        private synchronized void unreachable(Throwable error) {
            if (closed) {
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (!opened) {
                fail.accept("Could not reach the API's signaling: " + ApiHttp.reason(error));
            } else if (cause instanceof WebSocketHandshakeException refused) {
                // The API answered and refused: the ticket's seat is gone (its room closed, or the API restarted).
                fail.accept("The API ended this P2P session (HTTP " + refused.getResponse().statusCode() + ").");
            } else if (System.nanoTime() - lostAt > RETURN_NANOS) {
                fail.accept("Lost signaling with the API: " + ApiHttp.reason(error));
            } else {
                retry();
            }
        }

        /** {@code socket} is gone: another is opened, unless this is closed or already has one in its place. */
        private synchronized void lost(WebSocket socket, String how) {
            if (closed || socket != ws) {
                return;
            }
            ws = null;
            lostAt = System.nanoTime();
            LOGGER.info("Lost P2P signaling ({}); connecting again", how);
            retry();
        }

        private void retry() {
            CompletableFuture.delayedExecutor(retryMillis, TimeUnit.MILLISECONDS).execute(this::connect);
        }

        @Override
        public synchronized void onOpen(WebSocket webSocket) {
            if (closed) {
                webSocket.abort();
                return;
            }
            if (opened) {
                LOGGER.info("P2P signaling is back");
            }
            opened = true;
            ws = webSocket;
            partial.setLength(0);
            sending = CompletableFuture.completedFuture(null);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > MAX_SIGNAL) {
                webSocket.abort();
                fail.accept("The API sent a signaling message over 64 KiB.");
                return null;
            }
            if (last) {
                signal.accept(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.abort();
            fail.accept("The API sent binary data over signaling.");
            return null;
        }

        /** Also for a socket cut without a Close frame, which the JDK reports as closed with 1006. */
        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            lost(webSocket, "closed with " + statusCode + (reason.isBlank() ? "" : ": " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lost(webSocket, ApiHttp.reason(error));
        }

        synchronized void send(String text) {
            WebSocket socket = ws;
            if (socket == null) {
                // ponytail: what is signalled while the socket is away is dropped. That is only an offer or candidate
                // made in the moment it was lost, whose connection then times out; queue them if that ever shows.
                return;
            }
            if (++outstanding > 256) {
                socket.abort();
                fail.accept("Signaling could not keep up.");
                return;
            }
            sending = sending.thenCompose(v -> socket.sendText(text, true)).whenComplete((v, e) -> {
                synchronized (this) {
                    outstanding--;
                }
            });
        }

        synchronized void close() {
            closed = true;
            WebSocket socket = ws;
            if (socket == null) {
                http.shutdownNow();
                return;
            }
            sending.thenCompose(v -> socket.sendClose(WebSocket.NORMAL_CLOSURE, ""))
                    .orTimeout(2, TimeUnit.SECONDS)
                    .whenComplete((v, e) -> {
                        socket.abort();
                        http.shutdownNow();
                    });
        }
    }
}
