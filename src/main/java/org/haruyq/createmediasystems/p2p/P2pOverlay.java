package org.haruyq.createmediasystems.p2p;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.disc.DiscClient;
import org.haruyq.createmediasystems.disc.DiscSource;
import org.haruyq.createmediasystems.disc.DiscStream;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * {@code /cms p2p debug}: text in the top-left corner on this client's P2P session, mostly its connection to the host:
 * the route, round trip, measured rate, what is coming in, and the disc segment it waits for. As host, a line per
 * viewer. While shown, it pings each peer once a second, quietly. Hidden with the HUD and under the F3 screen.
 */
public final class P2pOverlay {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55, RED = 0xFFFF5555;
    private static final long SECOND = 1_000_000_000L;
    /** Game thread only. */
    private static boolean shown;
    private static long lastPing, windowAt, windowBytes;
    private static double receivingBps;

    record Line(String text, int color) {
    }

    private P2pOverlay() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterGuiLayersEvent event) ->
                event.registerAboveAll(CreateMediaSystems.id("p2p_debug"), P2pOverlay::render));
    }

    /** Shows or hides it; whether it is shown now. */
    static boolean toggle() {
        shown = !shown;
        windowAt = 0;
        return shown;
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!shown || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Transport transport = P2pClient.current();
        long now = System.nanoTime();
        if (transport != null && now - lastPing >= SECOND) {
            lastPing = now;
            transport.measure();
            long received = transport.received();
            if (windowAt != 0) {
                receivingBps = (received - windowBytes) * 8e9 / (now - windowAt);
            }
            windowAt = now;
            windowBytes = received;
        }
        List<Line> lines = transport == null ? List.of(new Line("CMS P2P: not in a session", GRAY))
                : lines(transport.state(), transport.host(), transport.hostId(), transport.received(), transport.links(),
                        DiscClient.stats(), receivingBps, now, P2pOverlay::name);
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

    /** The overlay's text for a session as of {@code now}; {@code name} gives a player's name. */
    static List<Line> lines(Transport.State session, boolean hosting, UUID hostId, long received, List<Transport.LinkInfo> links,
                            DiscStream.Stats disc, double receivingBps, long now, Function<UUID, String> name) {
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
            if (disc != null && disc.host() && disc.encoder() != null) {
                lines.add(new Line("Disc: " + disc.encoder() + (disc.encoded() < 0 ? ""
                        : ", segment " + disc.encoded() + " at " + disc.quality()), GRAY));
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
        if (disc != null && !disc.host()) {
            if (disc.waitingSince() != 0) {
                long waited = now - disc.waitingSince();
                // A segment plays for SEGMENT_SECONDS: waiting longer than that, playback has to stall.
                int color = waited > DiscSource.SEGMENT_SECONDS * SECOND ? RED : waited > SECOND * 2 ? YELLOW : WHITE;
                lines.add(new Line("Disc: waiting for " + (disc.waiting() < 0 ? "the length" : "segment " + disc.waiting())
                        + " for " + seconds(waited), color));
            } else {
                lines.add(new Line("Disc: idle", GRAY));
            }
            if (disc.last() >= 0) {
                lines.add(new Line("Last segment " + disc.last() + ": " + bytes(disc.lastBytes()) + " in " + seconds(disc.lastNanos())
                        + " (" + mbps((long) (disc.lastBytes() * 8e9 / Math.max(1, disc.lastNanos()))) + ")", WHITE));
            }
            if (disc.failures() > 0) {
                lines.add(new Line("Failed requests: " + disc.failures() + (disc.error() == null ? "" : ", last: " + disc.error()), RED));
            }
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

    static String mbps(long bps) {
        return String.format(Locale.ROOT, "%.1f Mbps", bps / 1e6);
    }

    static String bytes(long bytes) {
        return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
                : bytes < 1L << 30 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }

    private static String duration(long nanos) {
        long seconds = nanos / SECOND;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
