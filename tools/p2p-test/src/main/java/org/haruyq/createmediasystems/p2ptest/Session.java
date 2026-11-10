package org.haruyq.createmediasystems.p2ptest;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.haruyq.createmediasystems.p2p.Protocol;
import org.haruyq.createmediasystems.p2p.Transport;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import com.google.gson.JsonObject;

/**
 * One host or viewer of a test room: gets its ticket from the API with the test server token, then talks through the
 * mod's own Transport. A host sends JPEG frames of its source at the room's budget, dropping those it has no budget
 * for; a viewer decodes the latest frame it got. Nothing here blocks its caller, and nothing is reported once closed.
 */
final class Session {
    static final int FPS = 10;
    // Never follows redirects, so the token goes to the API typed in only. HTTP/1.1: uvicorn refuses h2c upgrades.
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    interface Listener {
        /** The room this host opened. */
        default void room(String id) {
        }

        void status(String message);

        /** The host's own frame, or the latest one a viewer decoded. */
        void frame(BufferedImage image, long sequence);
    }

    private final String api;
    private final String token;
    private final String room;
    private final long capBps;
    private final Listener listener;
    /** Sends (host) or decodes (viewer), one frame at a time. */
    final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("CMS P2P test").factory());
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private volatile Protocol.Ticket ticket;
    private volatile Transport transport;
    private volatile boolean closed;
    /** The screen to share, chosen by the user; null: the test pattern. */
    private volatile GraphicsDevice screen;
    private Robot robot;
    private long sequence;
    private double allowance;
    private long lastSend = System.nanoTime();
    /**
     * Frames sent (host) or decoded (viewer), payload bytes the host handed to its Transport, frames dropped for the
     * budget or a backlog, and bad ones received.
     */
    final AtomicLong frames = new AtomicLong(), sent = new AtomicLong(), dropped = new AtomicLong(), bad = new AtomicLong();

    /**
     * Hosts a room (if {@code room} is null) or joins it. A host sends frames at what its Transport measured, or at
     * most {@code capBps} if that is over 0, to try a slower connection. Throws IllegalArgumentException, with what
     * to fix, for an unusable API URL, room ID or cap.
     */
    Session(String api, String token, String room, long capBps, Listener listener) {
        this.api = api;
        this.capBps = capBps;
        this.token = token;
        this.room = room;
        this.listener = listener;
        try {
            Protocol.endpoint(api, "p2p", false);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("API URLは https:// で始めてください（このPCのAPIなら http://127.0.0.1:ポート も可）。");
        }
        if (!token.matches("[\\x21-\\x7e]+")) {
            throw new IllegalArgumentException("テスト用サーバートークンを入れてください（APIの P2P_SERVER_TOKEN と同じ値）。");
        }
        if (room != null && !Protocol.SESSION_ID.matcher(room).matches()) {
            throw new IllegalArgumentException("ルームIDの形式が違います（英数字・-・_ の8〜128文字）。");
        }
        if (capBps < 0) {
            throw new IllegalArgumentException("送信上限は 0 以上にしてください。");
        }
        UUID self = UUID.randomUUID();
        JsonObject body = new JsonObject();
        body.addProperty(room == null ? "host_id" : "peer_id", self.toString());
        call("POST", room == null ? "p2p/sessions" : "p2p/sessions/" + room + "/peers", body.toString())
                .whenComplete((response, error) -> {
                    try {
                        opened(self, response, error);
                    } catch (RuntimeException | LinkageError e) {
                        fail("P2P を始められません: " + UrlPolicy.reason(e));
                    }
                });
    }

    private CompletableFuture<HttpResponse<String>> call(String method, String path, String body) {
        return HTTP.sendAsync(HttpRequest.newBuilder(Protocol.endpoint(api, path, false))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void opened(UUID self, HttpResponse<String> response, Throwable error) {
        if (error != null) {
            fail("APIに接続できません（" + UrlPolicy.reason(error) + "）。API URLと、APIが動いているかを確認してください。");
            return;
        }
        String detail = UrlPolicy.errorDetail(response.statusCode(), response.body());
        switch (response.statusCode()) {
            case 200 -> {
            }
            case 401 -> {
                fail("テスト用サーバートークンが違います。APIの P2P_SERVER_TOKEN と同じ値を入れてください。");
                return;
            }
            case 404 -> {
                fail("ルームが見つかりません。ルームIDと、ホストがまだ開いているかを確認してください。");
                return;
            }
            case 409 -> {
                fail("このルームには参加できません（" + detail + "）。");
                return;
            }
            default -> {
                fail("APIが断りました（HTTP " + response.statusCode() + ": " + detail + "）。");
                return;
            }
        }
        Protocol.Ticket got;
        try {
            got = Protocol.Ticket.parse(response.body());
            if (!got.peerId().equals(self) || got.host() != (room == null) || room != null && !got.sessionId().equals(room)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            fail("APIの答えがCMSのP2Pチケットではありません。API URLを確認してください。");
            return;
        }
        synchronized (this) {
            ticket = got;
            if (closed) {
                revoke(got);
                return;
            }
            transport = Transport.open(api, got, new Transport.Listener() {
                @Override
                public void data(UUID from, byte[] payload) {
                    if (received.getAndSet(payload) == null) {
                        run(Session.this::decode);
                    }
                }

                @Override
                public void status(String message) {
                    report("P2P: " + message);
                    ended();
                }
            });
        }
        // It may have ended before it was assigned, as when native WebRTC fails to load.
        ended();
        if (room == null && !closed) {
            listener.room(got.sessionId());
            try {
                worker.scheduleAtFixedRate(this::sendFrame, 0, 1000 / FPS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Closed.
            }
        }
    }

    /** Closes this session if its Transport ended by itself (the host left, no direct route): the room or seat goes too. */
    private void ended() {
        Transport t = transport;
        if (t != null && t.state() == Transport.State.CLOSED) {
            close();
        }
    }

    /** Shares {@code screen} from the next frame on; null goes back to the test pattern. */
    void share(GraphicsDevice screen) {
        this.screen = screen;
    }

    /** Worker only. */
    private void sendFrame() {
        try {
            BufferedImage image = capture();
            long number = sequence++;
            report(image, number);
            Transport t = transport;
            long budget = capBps > 0 ? Math.min(t.budgetBps(), capBps) : t.budgetBps();
            long now = System.nanoTime();
            // At most the budget on average; a frame over it is let through, then later ones wait it out.
            allowance = Math.min(budget / 8.0 / 2, allowance + budget / 8.0 * (now - lastSend) / 1e9);
            lastSend = now;
            if (budget <= 0 || t.connected().isEmpty()) {
                return;
            }
            // Nor any while a payload's worth still waits to go out: a line slower than measured drops frames
            // instead of showing ones seconds old.
            byte[] payload = allowance < 0 || t.backlog() > Transport.MAX_PAYLOAD ? null
                    : Frames.encode(image, number, (int) Math.min(Transport.MAX_PAYLOAD, budget / 8 / FPS));
            if (payload == null || !t.send(payload)) {
                dropped.incrementAndGet();
                return;
            }
            allowance -= payload.length;
            sent.addAndGet(payload.length);
            frames.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            report("フレームを送れませんでした: " + UrlPolicy.reason(e));
        }
    }

    private BufferedImage capture() {
        GraphicsDevice device = screen;
        if (device != null) {
            try {
                if (robot == null) {
                    robot = new Robot();
                }
                return Frames.fit(robot.createScreenCapture(device.getDefaultConfiguration().getBounds()));
            } catch (AWTException | RuntimeException e) {
                screen = null;
                report("画面を取り込めないので、テストパターンを送ります: " + UrlPolicy.reason(e));
            }
        }
        return Frames.pattern(sequence);
    }

    /** Worker only: decodes the latest frame received; those that came before it are dropped. */
    private void decode() {
        byte[] payload = received.getAndSet(null);
        if (payload == null) {
            return;
        }
        Frames.Frame frame = Frames.decode(payload);
        if (frame == null) {
            bad.incrementAndGet();
        } else {
            frames.incrementAndGet();
            report(frame.image(), frame.sequence());
        }
    }

    Transport transport() {
        return transport;
    }

    boolean host() {
        return room == null;
    }

    boolean closed() {
        return closed;
    }

    /** What the user sees as this session's state. */
    String state() {
        Transport t = transport;
        if (t != null && t.state() == Transport.State.CLOSED) {
            return "終了";
        }
        if (closed) {
            return "切断";
        }
        if (t == null) {
            return "APIにチケットを要求中";
        }
        if (t.state() == Transport.State.CONNECTING) {
            return "シグナリングに接続中";
        }
        int peers = t.connected().size();
        return peers > 0 ? (t.host() ? "直接接続済み（視聴者 " + peers + " 人）" : "ホストと直接接続済み")
                : t.host() ? "視聴者を待っています" : "ホストと直接接続中";
    }

    /** Leaves, and revokes this ticket at the API; done when the API answered (or failed to). */
    CompletableFuture<?> close() {
        Transport t;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.completedFuture(null);
            }
            closed = true;
            t = transport;
        }
        worker.shutdownNow();
        if (t != null) {
            t.close();
        }
        Protocol.Ticket got = ticket;
        return got == null ? CompletableFuture.completedFuture(null) : revoke(got);
    }

    private CompletableFuture<?> revoke(Protocol.Ticket got) {
        try {
            return call("DELETE", got.host() ? "p2p/sessions/" + got.sessionId()
                    : "p2p/sessions/" + got.sessionId() + "/peers/" + got.peerId(), null).handle((r, e) -> null);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private void fail(String message) {
        report(message);
        close();
    }

    private void run(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // Closed.
        }
    }

    private void report(String message) {
        if (!closed) {
            listener.status(message);
        }
    }

    private void report(BufferedImage image, long number) {
        if (!closed) {
            listener.frame(image, number);
        }
    }
}
