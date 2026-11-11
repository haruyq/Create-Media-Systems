package org.haruyq.createmediasystems.p2p;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * {@code /cms p2p debug}: text in the top-left corner on each of this client's P2P sessions, mostly its connection to the host:
 * the route, round trip, measured rate and what is coming in, then what each feature sending over it adds (see
 * Section), such as the disc segment it waits for. As host, a line per viewer. While shown, it pings each peer once a second, quietly. Hidden with the HUD and under the F3 screen.
 */
public final class P2pOverlay {
    public static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55, RED = 0xFFFF5555;
    public static final long SECOND = 1_000_000_000L;
    private static final List<Section> SECTIONS = new CopyOnWriteArrayList<>();
    /** Game thread only. */
    private static boolean shown;
    private static long lastPing;
    /** What each session took in as of its last look, and the rate since the one before. */
    private static final Map<Transport, Window> WINDOWS = new WeakHashMap<>();

    private static final class Window {
        long at, bytes;
        double bps;
    }

    public record Line(String text, int color) {
    }

    /** What a feature that sends over the sessions shows below their lines, as of {@code now}. */
    public interface Section {
        List<Line> lines(long now);
    }

    private P2pOverlay() {
    }

    /** Adds {@code section} below the session's lines (as the client starts). */
    public static void add(Section section) {
        SECTIONS.add(section);
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterGuiLayersEvent event) ->
                event.registerAboveAll(CreateMediaSystems.id("p2p_debug"), P2pOverlay::render));
    }

    static boolean toggle() {
        shown = !shown;
        WINDOWS.clear();
        return shown;
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!shown || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<Transport> sessions = P2pClient.sessions();
        long now = System.nanoTime();
        boolean look = now - lastPing >= SECOND;
        List<Line> lines = new ArrayList<>();
        for (Transport transport : sessions) {
            Window window = WINDOWS.computeIfAbsent(transport, t -> new Window());
            if (look) {
                lastPing = now;
                transport.measure();
                long received = transport.received();
                if (window.at != 0) {
                    window.bps = (received - window.bytes) * 8e9 / (now - window.at);
                }
                window.at = now;
                window.bytes = received;
            }
            lines.addAll(lines(transport.state(), transport.host(), transport.hostId(), transport.received(), transport.links(),
                    window.bps, now, P2pOverlay::name));
        }
        if (sessions.isEmpty()) {
            lines.add(new Line("CMS P2P: not in a session", GRAY));
        } else {
            for (Section section : SECTIONS) {
                lines.addAll(section.lines(now));
            }
        }
        int width = 0;
        for (Line line : lines) {
            width = Math.max(width, mc.font.width(line.text()));
        }
        int x = 4, y = 4, height = lines.size() * (mc.font.lineHeight + 1);
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 1, 0x90000000);
        for (Line line : lines) {
            graphics.drawString(mc.font, line.text(), x, y, line.color(), false);
            y += mc.font.lineHeight + 1;
        }
    }

    static List<Line> lines(Transport.State session, boolean hosting, UUID hostId, long received, List<Transport.LinkInfo> links,
                            double receivingBps, long now, Function<UUID, String> name) {
        List<Line> lines = new ArrayList<>();
        String state = session.name().toLowerCase(Locale.ROOT);
        if (hosting) {
            long open = links.stream().filter(Transport.LinkInfo::open).count();
            lines.add(new Line("CMS P2P: hosting, " + state + ", " + open + " viewer(s) connected", WHITE));
            for (Transport.LinkInfo link : links) {
                lines.add(link.open()
                        ? new Line(name.apply(link.peer()) + ": " + link.route() + ", RTT " + rtt(link.rttNanos()) + ", pace "
                                + mbps(link.bps()) + (link.measured() ? "" : " (measuring)") + ", queued " + bytes(link.backlog()),
                                rttColor(link.rttNanos()))
                        : new Line(name.apply(link.peer()) + ": connecting...", YELLOW));
            }
            return lines;
        }
        lines.add(new Line("CMS P2P: viewing " + name.apply(hostId) + ", " + state, WHITE));
        Transport.LinkInfo host = links.stream().filter(l -> l.peer().equals(hostId)).findFirst().orElse(null);
        if (host == null || !host.open()) {
            lines.add(new Line("Host: connecting...", YELLOW));
        } else {
            lines.add(new Line("Host: " + host.route() + ", RTT " + rtt(host.rttNanos()) + ", open "
                    + duration(now - host.openedAt()), rttColor(host.rttNanos())));
            lines.add(new Line("Measured " + (host.measured() ? mbps(host.bps()) : "(measuring)") + ", receiving "
                    + mbps((long) receivingBps) + ", " + bytes(received) + " in all", WHITE));
        }
        return lines;
    }

    private static String name(UUID player) {
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player);
        return info != null ? info.getProfile().getName() : player.toString().substring(0, 8);
    }

    static String rtt(long nanos) {
        return nanos <= 0 ? "-" : String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
    }

    private static int rttColor(long nanos) {
        return nanos <= 0 ? WHITE : nanos < 60_000_000L ? GREEN : nanos < 150_000_000L ? YELLOW : RED;
    }

    public static String mbps(long bps) {
        return P2pClient.mbps(bps) + " Mbps";
    }

    public static String bytes(long bytes) {
        return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
                : bytes < 1L << 30 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
    }

    public static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }

    private static String duration(long nanos) {
        long seconds = nanos / SECOND;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
