package org.haruyq.createmediasystems.p2p;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /cms_p2p}: this client's side of direct P2P (see P2p). speedtest measures here; host, join and leave ask
 * the server, which hands back a ticket; status and ping report on the session. Game thread only, but for the
 * speedtest's own thread and the Transport's, which come back to it.
 * <p>
 * Each host or join is a numbered request, and its ticket, session and leave carry that number: whatever comes
 * for a request this client no longer wants is dropped, and the server ignores a leave for one it replaced.
 */
public final class P2pClient {
    private static Transport transport;
    /** The request whose ticket opened {@link #transport} (or is opening it); 0 if none. */
    private static int session;
    /** The latest host or join; 0 once left. Numbers are never reused, not even on another server. */
    private static int wanted;
    private static int requests;
    static Speedtest.Result measured;
    private static Thread speedtest;
    /** The game thread, the server, its API, and how a ticket's session opens there; other in tests. */
    static Executor game = task -> Minecraft.getInstance().execute(task);
    static Consumer<CustomPacketPayload> server = PacketDistributor::sendToServer;
    static Supplier<String> api = ServerConfig.API_URL::get;
    static Opener open = (api, ticket, listener) -> {
        try {
            Transport.loadNatives(ClientConfig.DIR.resolve("webrtc"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Transport.open(api, ticket, listener);
    };

    interface Opener {
        Transport open(String api, P2p.Ticket ticket, Transport.Listener listener);
    }

    private P2pClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(P2pClient::registerCommands);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            logout();
            if (speedtest != null) {
                speedtest.interrupt();
            }
        });
    }

    /** This client's session, for what sends or receives over it; null if none. */
    public static Transport current() {
        return transport;
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cms_p2p")
                .then(Commands.literal("speedtest").executes(c -> speedtest())
                        .then(Commands.literal("cancel").executes(c -> cancelSpeedtest())))
                .then(Commands.literal("host").executes(c -> request(P2p.Action.HOST, "")))
                .then(Commands.literal("join").then(Commands.argument("host", StringArgumentType.word())
                        .suggests((c, builder) -> SharedSuggestionProvider.suggest(players(), builder))
                        .executes(c -> request(P2p.Action.JOIN, StringArgumentType.getString(c, "host")))))
                .then(Commands.literal("leave").executes(c -> leave()))
                .then(Commands.literal("status").executes(c -> status()))
                .then(Commands.literal("ping").executes(c -> {
                    if (transport == null) {
                        say(Component.translatable("create_media_systems.p2p.none"), true);
                        return 0;
                    }
                    transport.ping();
                    return 1;
                })));
    }

    private static int speedtest() {
        if (speedtest != null && speedtest.isAlive()) {
            say(Component.translatable("create_media_systems.p2p.speedtest.busy"), true);
            return 0;
        }
        String executable = ClientConfig.SPEEDTEST_PATH.get();
        say(Component.translatable("create_media_systems.p2p.speedtest.running"), false);
        speedtest = Thread.ofPlatform().daemon().name("CMS speedtest").start(() -> {
            try {
                Speedtest.Result result = Speedtest.run(executable);
                game.execute(() -> {
                    measured = result;
                    say(Component.translatable("create_media_systems.p2p.speedtest.done",
                            mbps(result.downloadBps()), mbps(result.uploadBps())), false);
                });
            } catch (Speedtest.Failure e) {
                game.execute(() -> say(Component.literal(e.getMessage()), true));
            } catch (InterruptedException e) {
                game.execute(() -> say(Component.translatable("create_media_systems.p2p.speedtest.cancelled"), false));
            }
        });
        return 1;
    }

    private static int cancelSpeedtest() {
        if (speedtest == null || !speedtest.isAlive()) {
            say(Component.translatable("create_media_systems.p2p.speedtest.idle"), true);
            return 0;
        }
        speedtest.interrupt();
        return 1;
    }

    /**
     * Asks the server to host or join. The current session stays until the server ends it for this request, so a
     * refused one (cooldown, no such room) keeps it.
     */
    static int request(P2p.Action action, String host) {
        if (host.length() > 16) {
            say(Component.translatable("create_media_systems.p2p.no_room", host), true);
            return 0;
        }
        if (measured == null || !measured.fresh(Instant.now())) {
            say(Component.translatable("create_media_systems.p2p.speedtest.required"), true);
            return 0;
        }
        wanted = ++requests;
        server.accept(new P2p.Request(action, wanted, host, measured.uploadBps(), measured.downloadBps()));
        say(Component.translatable("create_media_systems.p2p.requested"), false);
        return 1;
    }

    /** Leaves the session and drops the request in flight, whichever of them the server has as this player's. */
    static int leave() {
        int open = session;
        closeTransport();
        if (open != 0) {
            server.accept(new P2p.Request(P2p.Action.LEAVE, open, "", 0, 0));
        }
        if (wanted != 0 && wanted != open) {
            server.accept(new P2p.Request(P2p.Action.LEAVE, wanted, "", 0, 0));
        }
        wanted = 0;
        say(Component.translatable("create_media_systems.p2p.left"), false);
        return 1;
    }

    static void logout() {
        closeTransport();
        wanted = 0;
    }

    private static int status() {
        say(measured == null ? Component.translatable("create_media_systems.p2p.status.unmeasured")
                : Component.translatable("create_media_systems.p2p.status.measured", mbps(measured.downloadBps()),
                        mbps(measured.uploadBps()), (Instant.now().getEpochSecond() - measured.measuredAt().getEpochSecond()) / 60), false);
        Transport t = transport;
        say(t == null ? Component.translatable("create_media_systems.p2p.none")
                : Component.translatable("create_media_systems.p2p.status." + (t.host() ? "host" : "viewer"),
                        t.state().name().toLowerCase(Locale.ROOT), t.connected().size(), mbps(t.budgetBps()), t.received()), false);
        return 1;
    }

    /** The server's ticket for this player: opens its session in place of any other, if its request is still wanted. */
    static void ticket(P2p.Ticket ticket) {
        int request = ticket.request();
        if (request != wanted) {
            // Left or asked again since: the server revoked it.
            return;
        }
        closeTransport();
        session = request;
        // The API of the server that gave the ticket: it goes nowhere else, even if this client moves on while it opens.
        String origin = api.get();
        Transport.Listener listener = message -> game.execute(() -> {
            if (session != request) {
                return;
            }
            say(Component.literal(named(message)), false);
            // A session that ended by itself (host left, connection failed) frees this player on the server too.
            if (transport != null && transport.state() == Transport.State.CLOSED) {
                ended(request);
            }
        });
        CompletableFuture.supplyAsync(() -> open.open(origin, ticket, listener)).whenComplete((opened, error) -> game.execute(() -> {
            if (session != request) {
                // Left, logged out or replaced while it opened.
                if (opened != null) {
                    opened.close();
                }
            } else if (error != null) {
                say(Component.translatable("create_media_systems.p2p.natives_failed", UrlPolicy.reason(error)), true);
                ended(request);
            } else if (opened.state() == Transport.State.CLOSED) {
                ended(request);
            } else {
                transport = opened;
            }
        }));
    }

    /** Request {@code request}'s session is over: the server frees this player, unless it has a later request. */
    private static void ended(int request) {
        closeTransport();
        if (wanted == request) {
            wanted = 0;
        }
        server.accept(new P2p.Request(P2p.Action.LEAVE, request, "", 0, 0));
    }

    static void notice(Component message) {
        say(message, true);
    }

    private static void closeTransport() {
        session = 0;
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    /** {@code message} with the players' UUIDs in it as their names. */
    private static String named(String message) {
        ClientPacketListener connection = Minecraft.getInstance() == null ? null : Minecraft.getInstance().getConnection();
        if (connection != null) {
            for (PlayerInfo player : connection.getOnlinePlayers()) {
                message = message.replace(player.getProfile().getId().toString(), player.getProfile().getName());
            }
        }
        return message;
    }

    private static Iterable<String> players() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection == null ? List.of()
                : connection.getOnlinePlayers().stream().map(p -> p.getProfile().getName()).toList();
    }

    private static String mbps(long bps) {
        return String.format(Locale.ROOT, "%.1f", bps / 1e6);
    }

    private static void say(Component message, boolean failure) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(failure ? message.copy().withStyle(ChatFormatting.RED) : message, false);
        }
    }
}
