package org.haruyq.createmediasystems.p2p;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * {@code /cms p2p}: this client's side of direct P2P (see P2p). host, join and leave ask the server, which hands
 * back a ticket; status, ping and debug (see P2pOverlay) report on the session, whose connections measure themselves (see LinkRate). Game
 * thread only, but for the Transport's, which comes back to it.
 * <p>
 * Each host or join is a numbered request, and its ticket, session and leave carry that number: whatever comes
 * for a request this client no longer wants is dropped, and the server ignores a leave for one it replaced.
 */
public final class P2pClient {
    private static volatile Transport transport;
    private static volatile Transport.Listener media;
    private static Consumer<Transport> changed = session -> {};
    private static UUID joining;
    /** The request whose ticket opened {@link #transport} (or is opening it); 0 if none. */
    private static volatile int session;
    /** The latest host or join; 0 once left. Numbers are never reused, not even on another server. */
    private static int wanted;
    private static int requests;
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
        Transport open(String api, Protocol.Ticket ticket, Transport.Listener listener);
    }

    private P2pClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(P2pClient::registerCommands);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> logout());
    }

    /** This client's session, for what sends or receives over it; null if none. */
    public static Transport current() {
        return transport;
    }

    public static void media(Transport.Listener listener) {
        media = listener;
    }

    public static void changed(Consumer<Transport> listener) {
        changed = listener;
    }

    public static boolean connect(UUID owner) {
        if (transport != null && transport.state() != Transport.State.CLOSED && transport.hostId().equals(owner)) {
            return true;
        }
        if (owner.equals(joining)) {
            return true;
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return false;
        }
        PlayerInfo player = mc.getConnection().getPlayerInfo(owner);
        if (player == null) {
            return false;
        }
        boolean host = owner.equals(mc.player.getUUID());
        if (request(host ? P2p.Action.HOST : P2p.Action.JOIN, host ? "" : player.getProfile().getName()) == 0) {
            return false;
        }
        joining = owner;
        return true;
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        // A client command under the server's /cms: NeoForge hands what no client command matches (/cms config) to the server.
        event.getDispatcher().register(Commands.literal("cms").then(Commands.literal("p2p")
                .then(Commands.literal("host").executes(c -> request(P2p.Action.HOST, "")))
                .then(Commands.literal("join").then(Commands.argument("host", StringArgumentType.word())
                        .suggests((c, builder) -> SharedSuggestionProvider.suggest(players(), builder))
                        .executes(c -> request(P2p.Action.JOIN, StringArgumentType.getString(c, "host")))))
                .then(Commands.literal("leave").executes(c -> leave()))
                .then(Commands.literal("status").executes(c -> status()))
                .then(Commands.literal("debug").executes(c -> {
                    say(Component.translatable(P2pOverlay.toggle() ? "create_media_systems.p2p.debug.on"
                            : "create_media_systems.p2p.debug.off"), false);
                    return 1;
                }))
                .then(Commands.literal("ping").executes(c -> {
                    if (transport == null) {
                        say(Component.translatable("create_media_systems.p2p.none"), true);
                        return 0;
                    }
                    transport.ping();
                    return 1;
                }))));
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
        wanted = ++requests;
        server.accept(new P2p.Request(action, wanted, host));
        say(Component.translatable("create_media_systems.p2p.requested"), false);
        return 1;
    }

    /** Leaves the session and drops the request in flight, whichever of them the server has as this player's. */
    static int leave() {
        int open = session;
        closeTransport();
        if (open != 0) {
            server.accept(new P2p.Request(P2p.Action.LEAVE, open, ""));
        }
        if (wanted != 0 && wanted != open) {
            server.accept(new P2p.Request(P2p.Action.LEAVE, wanted, ""));
        }
        wanted = 0;
        say(Component.translatable("create_media_systems.p2p.left"), false);
        return 1;
    }

    static void logout() {
        closeTransport();
        wanted = 0;
        joining = null;
    }

    private static int status() {
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
        joining = ticket.ticket().hostId();
        // The API of the server that gave the ticket: it goes nowhere else, even if this client moves on while it opens.
        String origin = api.get();
        Transport.Listener listener = new Transport.Listener() {
            @Override
            public void data(UUID from, byte[] payload) {
                Transport.Listener receiver = media;
                if (session == request && receiver != null) {
                    receiver.data(from, payload);
                }
            }

            @Override
            public void control(UUID from, byte[] payload) {
                Transport.Listener receiver = media;
                if (session == request && receiver != null) {
                    receiver.control(from, payload);
                }
            }

            @Override
            public void status(String message) {
                game.execute(() -> {
                    if (session != request) {
                        return;
                    }
                    say(Component.literal(named(message)), false);
                    // A session that ended by itself frees this player on the server too.
                    if (transport != null && transport.state() == Transport.State.CLOSED) {
                        ended(request);
                    }
                });
            }
        };
        CompletableFuture.supplyAsync(() -> open.open(origin, ticket.ticket(), listener)).whenComplete((opened, error) -> game.execute(() -> {
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
                joining = null;
                changed.accept(opened);
            }
        }));
    }

    /** Request {@code request}'s session is over: the server frees this player, unless it has a later request. */
    private static void ended(int request) {
        closeTransport();
        if (wanted == request) {
            wanted = 0;
        }
        server.accept(new P2p.Request(P2p.Action.LEAVE, request, ""));
    }

    static void notice(Component message) {
        joining = null;
        say(message, true);
    }

    private static void closeTransport() {
        session = 0;
        joining = null;
        if (transport != null) {
            transport.close();
            transport = null;
            changed.accept(null);
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
