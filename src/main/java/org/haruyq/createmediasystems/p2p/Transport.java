package org.haruyq.createmediasystems.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
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

import org.haruyq.createmediasystems.stream.UrlPolicy;
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
 * The host sends payloads to its viewers, paced at the room's budget. Client only: it loads native WebRTC.
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
    private static final byte DATA = 0, PING = 1, PONG = 2, CONTROL = 3;
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
        Socket socket = new Socket();
        Transport transport = new Transport(ticket, listener, socket::send, () -> socket.close(http));
        socket.transport = transport;
        http.newWebSocketBuilder().header("Authorization", "Bearer " + ticket.token())
                .connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, socket)
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        transport.fail("Could not reach the API's signaling: " + UrlPolicy.reason(error));
                    }
                });
        return transport;
    }

    /**
     * Unpacks the bundled native WebRTC into {@code dir} (unless it already is) for webrtc-java to load from there.
     * Call before the first session; never on a dedicated server.
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
        Path target = dir.resolve(version);
        Path library = target.resolve(LIBRARY);
        if (!Files.isRegularFile(library)) {
            Files.createDirectories(target);
            Path partial = Files.createTempFile(target, LIBRARY, ".part");
            try (InputStream in = resource(LIBRARY)) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
                Files.move(partial, library, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        // webrtc-java's NativeLoader.LIBRARY_DIR_PROPERTY: loads it there, in place.
        System.setProperty("webrtc.java.library.dir", target.toAbsolutePath().toString());
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

    /** The room's budget per viewer, in bit/s, which sending is paced at; 0 while there are no viewers. */
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

    /** Bytes of payloads received. */
    public long received() {
        return received;
    }

    /** Peers with an open DataChannel. */
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
            for (Link link : links.values()) {
                if (link.open) {
                    link.sendNow(frame(PING, ByteBuffer.allocate(8).putLong(System.nanoTime()).array()));
                }
            }
        });
    }

    @Override
    public void close() {
        post(() -> shutDown(null));
    }

    void fail(String reason) {
        post(() -> shutDown(reason));
    }

    /** One message from the API's signaling. */
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
                long budget = message.get("budget_bps").getAsLong();
                if (budget < 0 || budget > Protocol.MAX_BPS) {
                    throw new IllegalArgumentException();
                }
                if (budget != budgetBps && budget > 0) {
                    listener.status(String.format(Locale.ROOT, "Budget per viewer: %.1f Mbps", budget / 1e6));
                }
                budgetBps = budget;
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

    /** Sends what the budget allows of each queue, and gives up on peers that took too long to connect. */
    private void tick() {
        long now = System.nanoTime();
        long most = 0;
        for (Link link : List.copyOf(links.values())) {
            if (!link.open) {
                if (now - link.createdAt > CONNECT_TIMEOUT_NANOS) {
                    drop(link, "Could not connect directly to " + name(link.peer) + " in 30 s. Both of you may be "
                            + "behind strict NATs or firewalls: try the same LAN, allowing UDP or forwarding a port.");
                }
                continue;
            }
            double rate = budgetBps / 8.0;
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
                most = Math.max(most, link.queued + link.channel.getBufferedAmount());
            }
        }
        backlog = most;
    }

    private static byte[] frame(byte type, byte[] body) {
        byte[] frame = new byte[body.length + 1];
        frame[0] = type;
        System.arraycopy(body, 0, frame, 1, body.length);
        return frame;
    }

    /** A frame from {@code link}'s peer. */
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
            case PING -> {
                if (link.channel.getBufferedAmount() < MAX_QUEUED) {
                    frame[0] = PONG;
                    link.sendNow(frame);
                }
            }
            case PONG -> {
                if (frame.length == 9) {
                    long sent = ByteBuffer.wrap(frame, 1, 8).getLong();
                    listener.status(String.format(Locale.ROOT, "%s: %.1f ms over a direct %s connection", name(link.peer),
                            (System.nanoTime() - sent) / 1e6, link.route));
                }
            }
            default -> { }
        }
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

    /** Releases a native resource, logging what it throws so that the ones after it are released too. */
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
            // Closed.
            after.run();
        }
    }

    private synchronized void run(Runnable task) {
        if (state != State.CLOSED) {
            try {
                task.run();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("Direct P2P failed", e);
                shutDown("Direct P2P failed: " + UrlPolicy.reason(e));
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

    /** The connection to one peer. */
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
                listener.status("Connected directly to " + name(peer) + ".");
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

        /** Sets the offer or answer just created as this side's, then signals it. */
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

    /** The JDK WebSocket to the API's signaling: text in, at most {@link #MAX_SIGNAL} per message, in order out. */
    static final class Socket implements WebSocket.Listener {
        Transport transport;
        private final StringBuilder partial = new StringBuilder();
        private WebSocket ws;
        private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
        private int outstanding;
        private boolean closed;

        @Override
        public synchronized void onOpen(WebSocket webSocket) {
            if (closed) {
                webSocket.abort();
                return;
            }
            ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > MAX_SIGNAL) {
                webSocket.abort();
                transport.fail("The API sent a signaling message over 64 KiB.");
                return null;
            }
            if (last) {
                transport.signal(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.abort();
            transport.fail("The API sent binary data over signaling.");
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            transport.fail("The API closed signaling" + (reason.isBlank() ? "." : ": " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            transport.fail("Signaling failed: " + UrlPolicy.reason(error));
        }

        synchronized void send(String text) {
            // Only ever answers to what came in, so never before onOpen.
            if (++outstanding > 256) {
                ws.abort();
                transport.fail("Signaling could not keep up.");
                return;
            }
            WebSocket socket = ws;
            sending = sending.thenCompose(v -> socket.sendText(text, true)).whenComplete((v, e) -> {
                synchronized (this) {
                    outstanding--;
                }
            });
        }

        synchronized void close(HttpClient http) {
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
