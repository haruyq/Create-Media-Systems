package org.haruyq.createmediasystems.p2ptest;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.haruyq.createmediasystems.p2p.Protocol;
import org.haruyq.createmediasystems.p2p.Transport;

/**
 * A small desktop test of CMS's direct P2P (see README): host a room or join one through a CMS-API, then send a test
 * pattern or a shared screen as JPEG frames over the mod's own Transport, and watch what arrives. Video only.
 */
public final class P2pTestApp {
    private static final int LOG_LINES = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Off the EDT: stats that touch the Transport, and closing sessions. */
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("CMS P2P test UI").factory());
    /** EDT only: open windows, and their sessions' revocations once closed. */
    private static final List<P2pTestApp> OPEN = new ArrayList<>();
    private static final List<CompletableFuture<?>> CLOSING = new ArrayList<>();
    private static Runnable onExit = () -> { };

    private record Source(String label, GraphicsDevice screen) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final JFrame frame;
    private final JTextField api = new JTextField(28);
    private final JPasswordField token = new JPasswordField(16);
    private final JSpinner up = mbps(), down = mbps();
    private final JComboBox<Source> source = new JComboBox<>();
    private final JTextField room = new JTextField(24);
    private final JButton host = new JButton("ホスト開始"), copy = new JButton("コピー"), join = new JButton("参加"),
            leave = new JButton("切断"), ping = new JButton("Ping");
    private final JLabel state = new JLabel("未接続"), stats = new JLabel(" ");
    private final JTextArea log = new JTextArea(4, 0);
    private final Video video = new Video();
    private final AtomicReference<String[]> shown = new AtomicReference<>();
    /** Which start the listener's callbacks belong to; anything from an earlier one is dropped. */
    private volatile Object generation;
    private volatile Session session;
    /** Where a host's new room ID also goes (the local demo's viewer window). */
    private Consumer<String> roomOpened = id -> { };
    private long lastFrames, lastBytes, lastAt = System.nanoTime();

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--smoke")) {
            smoke(Path.of(args[1]), 20_000_000);
            System.exit(0);
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        if (args.length == 2 && args[0].equals("--local-demo")) {
            System.out.println("ローカルの CMS-API を起動しています…");
            LocalApi local;
            try {
                local = LocalApi.start(Path.of(args[1]));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "CMS P2P 映像テスト", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
                return;
            }
            System.out.println("ローカルの CMS-API: " + local.url + "（このデモ専用。ウィンドウをすべて閉じると止まります）");
            Runtime.getRuntime().addShutdownHook(new Thread(local::close));
            onExit = local::close;
            SwingUtilities.invokeLater(() -> {
                P2pTestApp hostWindow = new P2pTestApp("ホスト", local.url, local.token);
                P2pTestApp viewerWindow = new P2pTestApp("視聴者", local.url, local.token);
                hostWindow.roomOpened = viewerWindow.room::setText;
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                hostWindow.frame.setLocation(screen.x, screen.y);
                viewerWindow.frame.setLocation(screen.x + Math.max(0, screen.width - viewerWindow.frame.getWidth()), screen.y);
            });
        } else if (args.length == 0) {
            String env = System.getenv("CMS_P2P_API_URL");
            String secret = System.getenv("CMS_P2P_TEST_TOKEN");
            SwingUtilities.invokeLater(() -> new P2pTestApp(null, env == null ? "https://" : env, secret == null ? "" : secret));
        } else {
            System.err.println("使い方: cms-p2p-test [--local-demo <CMS-APIのフォルダー> | --smoke <CMS-APIのフォルダー>]");
            System.exit(2);
        }
    }

    private P2pTestApp(String role, String apiUrl, String secret) {
        frame = new JFrame("CMS P2P 映像テスト" + (role == null ? "" : "（" + role + "）"));
        api.setText(apiUrl);
        token.setText(secret);
        token.setToolTipText("APIの P2P_SERVER_TOKEN。テスト用のAPIの値だけを使い、本番の秘密は渡さないでください");
        source.addItem(new Source("テストパターン", null));
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (int i = 0; i < screens.length; i++) {
            Rectangle bounds = screens[i].getDefaultConfiguration().getBounds();
            source.addItem(new Source("画面共有: 画面 " + (i + 1) + "（" + bounds.width + "×" + bounds.height + "）", screens[i]));
        }
        source.addActionListener(e -> {
            Session s = session;
            if (s != null && s.host()) {
                s.share(selected());
            }
        });
        log.setEditable(false);
        log.setLineWrap(true);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(row(new JLabel("API URL"), api, new JLabel("テスト用サーバートークン"), token));
        form.add(row(new JLabel("上り"), up, new JLabel("下り"), down, new JLabel("Mbps（手入力のテスト値。計測値ではありません）")));
        form.add(row(new JLabel("送信映像"), source, host));
        form.add(row(new JLabel("ルームID"), room, copy, join, leave, ping));

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        state.setFont(state.getFont().deriveFont(Font.BOLD, state.getFont().getSize2D() + 2));
        bottom.add(row(state));
        bottom.add(row(stats));
        bottom.add(row(new JLabel("映像のみ（音声なし）。JPEG を DataChannel で送る接続テストで、Blu-ray のコーデックや性能の検証ではありません。")));
        JScrollPane scroll = new JScrollPane(log);
        scroll.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        bottom.add(scroll);

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        content.add(form, BorderLayout.NORTH);
        content.add(video, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        frame.setContentPane(content);

        host.addActionListener(e -> start(null));
        join.addActionListener(e -> start(room.getText().strip()));
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(room.getText().strip()), null));
        leave.addActionListener(e -> stop());
        ping.addActionListener(e -> {
            Session s = session;
            Transport t = s == null ? null : s.transport();
            if (t != null) {
                t.ping();
            }
        });
        buttons(false);

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stop();
                OPEN.remove(P2pTestApp.this);
                if (OPEN.isEmpty()) {
                    exit();
                }
            }
        });
        frame.setSize(960, 640);
        frame.setLocationByPlatform(true);
        OPEN.add(this);
        frame.setVisible(true);
        TICKER.scheduleWithFixedDelay(this::refresh, 500, 500, TimeUnit.MILLISECONDS);
    }

    private static JSpinner mbps() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(20.0, 0.1, 10_000.0, 1.0));
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(5);
        return spinner;
    }

    private static JPanel row(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        for (JComponent component : components) {
            row.add(component);
        }
        return row;
    }

    private GraphicsDevice selected() {
        return ((Source) source.getSelectedItem()).screen();
    }

    /** Hosts a room (if {@code roomId} is null) or joins it, in place of this window's session. */
    private void start(String roomId) {
        stop();
        Object id = new Object();
        generation = id;
        long upBps = Math.round((Double) up.getValue() * 1e6), downBps = Math.round((Double) down.getValue() * 1e6);
        try {
            Session s = new Session(api.getText().strip(), new String(token.getPassword()), roomId, upBps, downBps, listener(id));
            session = s;
            if (roomId == null) {
                room.setText("");
                s.share(selected());
            }
            video.clear();
            lastFrames = lastBytes = 0;
            buttons(true);
            say(roomId == null ? "ルームを開いています…" : "ルーム " + roomId + " に参加しています…");
        } catch (IllegalArgumentException e) {
            generation = null;
            say(e.getMessage());
        }
    }

    private Session.Listener listener(Object id) {
        return new Session.Listener() {
            @Override
            public void room(String roomId) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == id) {
                        room.setText(roomId);
                        roomOpened.accept(roomId);
                        say("ルームを開きました。ルームIDを視聴者に伝えてください（トークンは含まれません）。");
                    }
                });
            }

            @Override
            public void status(String message) {
                SwingUtilities.invokeLater(() -> {
                    if (generation == id) {
                        say(message);
                    }
                });
            }

            @Override
            public void frame(BufferedImage image, long sequence) {
                if (generation == id) {
                    video.offer(image);
                }
            }
        };
    }

    /** Leaves this window's session, if any, closing it off the EDT. */
    private void stop() {
        Session s = session;
        generation = null;
        session = null;
        buttons(false);
        if (s != null) {
            CLOSING.add(CompletableFuture.supplyAsync(s::close, TICKER).thenCompose(f -> f));
            say("切断しました。");
        }
    }

    private void buttons(boolean active) {
        host.setEnabled(!active);
        join.setEnabled(!active);
        leave.setEnabled(active);
        ping.setEnabled(active);
    }

    private void say(String message) {
        log.append(LocalTime.now().format(TIME) + " " + message + "\n");
        int extra = log.getLineCount() - LOG_LINES;
        if (extra > 0) {
            try {
                log.replaceRange("", 0, log.getLineEndOffset(extra - 1));
            } catch (javax.swing.text.BadLocationException e) {
                log.setText("");
            }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    /** Off the EDT, as the Transport's getters may wait for its thread: works out the state and rates to show. */
    private void refresh() {
        Session s = session;
        long now = System.nanoTime();
        double seconds = Math.max(1e-3, (now - lastAt) / 1e9);
        lastAt = now;
        String[] text;
        if (s == null) {
            text = new String[] {"未接続", " "};
        } else {
            Transport t = s.transport();
            long frames = s.frames.get(), bytes = s.host() ? s.sent.get() : t == null ? 0 : t.received();
            double fps = (frames - lastFrames) / seconds, mbps = (bytes - lastBytes) * 8 / seconds / 1e6;
            lastFrames = frames;
            lastBytes = bytes;
            String budget = t == null ? "-" : String.format(Locale.ROOT, "%.1f Mbps", t.budgetBps() / 1e6);
            text = new String[] {(s.host() ? "ホスト: " : "視聴者: ") + s.state(), s.host()
                    ? String.format(Locale.ROOT, "予算 %s ／ 送信に渡した量 %.2f Mbps ／ 未送出 %d KB ／ %.1f fps ／ 送信 %d フレーム ／ 間引き %d",
                            budget, mbps, t == null ? 0 : t.backlog() / 1024, fps, frames, s.dropped.get())
                    : String.format(Locale.ROOT, "予算 %s ／ 受信 %.2f Mbps ／ %.1f fps ／ 表示 %d フレーム ／ 壊れたフレーム %d",
                            budget, mbps, fps, frames, s.bad.get())};
        }
        if (shown.getAndSet(text) == null) {
            SwingUtilities.invokeLater(() -> {
                String[] latest = shown.getAndSet(null);
                state.setText(latest[0]);
                stats.setText(latest[1]);
                if (session != null && session.closed()) {
                    buttons(false);
                }
            });
        }
    }

    /** The last window closed: waits (3 s at most) for sessions to be revoked, then ends the demo's API and exits. */
    private static void exit() {
        CompletableFuture<?> closing = CompletableFuture.allOf(CLOSING.toArray(CompletableFuture[]::new));
        Thread.ofPlatform().start(() -> {
            try {
                closing.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Exiting anyway.
            }
            onExit.run();
            System.exit(0);
        });
    }

    /** The picture: the latest frame only, handed over to the EDT at most once at a time. */
    private static final class Video extends JComponent {
        private final AtomicReference<BufferedImage> next = new AtomicReference<>();
        private BufferedImage image;

        void offer(BufferedImage frame) {
            if (next.getAndSet(frame) == null) {
                SwingUtilities.invokeLater(() -> {
                    image = next.getAndSet(null);
                    repaint();
                });
            }
        }

        void clear() {
            image = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage shown = image;
            if (shown == null) {
                g.setColor(Color.GRAY);
                g.drawString("ホストを開始するか、ルームIDを入れて参加してください（映像のみ・音声なし）", 16, getHeight() / 2);
                return;
            }
            double scale = Math.min((double) getWidth() / shown.getWidth(), (double) getHeight() / shown.getHeight());
            int w = (int) (shown.getWidth() * scale), h = (int) (shown.getHeight() * scale);
            g.drawImage(shown, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        }
    }

    /**
     * Without a screen or user: a host and a viewer, both saying they have {@code bps}, through a CMS-API of their
     * own, checking that the viewer decodes changing frames no more than a second behind, then that both sessions,
     * the room and the API end. Throws on any failure; returns how many frames the host dropped.
     */
    static long smoke(Path apiDir, long bps) throws Exception {
        LocalApi local = LocalApi.start(apiDir);
        long dropped;
        try {
            CompletableFuture<String> roomId = new CompletableFuture<>();
            Map<Long, Integer> decoded = new ConcurrentHashMap<>();
            AtomicLong latest = new AtomicLong();
            Session host = new Session(local.url, local.token, null, bps, bps, new Session.Listener() {
                @Override
                public void room(String id) {
                    roomId.complete(id);
                }

                @Override
                public void status(String message) {
                    System.out.println("[host] " + message);
                }

                @Override
                public void frame(BufferedImage image, long sequence) {
                    latest.set(sequence);
                }
            });
            Session viewer = new Session(local.url, local.token, roomId.get(30, TimeUnit.SECONDS), bps, bps,
                    new Session.Listener() {
                        @Override
                        public void status(String message) {
                            System.out.println("[viewer] " + message);
                        }

                        @Override
                        public void frame(BufferedImage image, long sequence) {
                            if (image.getWidth() == Frames.WIDTH && image.getHeight() == Frames.HEIGHT) {
                                decoded.put(sequence, Arrays.hashCode(image.getRGB(0, 0, Frames.WIDTH, Frames.HEIGHT, null, 0, Frames.WIDTH)));
                            }
                        }
                    });
            await(() -> decoded.size() >= 10 && decoded.values().stream().distinct().count() >= 5, 30, "changing frames");
            long behind = latest.get() - decoded.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
            check(behind <= Session.FPS, "the viewer is " + behind + " frames behind");
            // floor(min(0.65 * bps / 1 viewer, 0.65 * bps))
            check(host.transport().budgetBps() == bps * 13 / 20, "budget " + host.transport().budgetBps());
            check(viewer.bad.get() == 0, viewer.bad.get() + " bad frames");
            dropped = host.dropped.get();
            System.out.printf(Locale.ROOT, "smoke at %d bit/s: viewer decoded %d frames (%d distinct, %d behind), %d bytes; "
                    + "host sent %d, dropped %d%n", bps, viewer.frames.get(), decoded.values().stream().distinct().count(),
                    behind, viewer.transport().received(), host.frames.get(), dropped);
            CompletableFuture.allOf(viewer.close(), host.close()).get(10, TimeUnit.SECONDS);
            await(() -> host.transport().state() == Transport.State.CLOSED && viewer.transport().state() == Transport.State.CLOSED
                    && host.worker.isTerminated() && viewer.worker.isTerminated(), 10, "sessions closed");
            // Joining the room again finds it gone.
            HttpResponse<String> gone = Session.HTTP.send(HttpRequest.newBuilder(Protocol.endpoint(local.url,
                            "p2p/sessions/" + roomId.get() + "/peers", false)).header("Authorization", "Bearer " + local.token)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"peer_id\":\""
                            + UUID.randomUUID() + "\",\"upload_bps\":1000000,\"download_bps\":1000000}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check(gone.statusCode() == 404, "room still open: HTTP " + gone.statusCode());
        } catch (Exception | AssertionError e) {
            System.out.println("CMS-API output:\n" + local.tail());
            throw e;
        } finally {
            local.close();
        }
        check(!local.alive(), "the local CMS-API still runs");
        System.out.println("smoke: OK");
        return dropped;
    }

    private static void await(BooleanSupplier condition, int seconds, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < end, "timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    private static void check(boolean ok, String failure) {
        if (!ok) {
            throw new IllegalStateException("smoke failed: " + failure);
        }
    }
}
