package org.haruyq.createmediasystems.p2p;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientFiles;

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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /cms p2p}: this client's side of direct P2P (see P2p). host, join and leave ask the server, which hands
 * back a ticket; status, ping and debug (see P2pOverlay) report on the sessions, whose connections measure themselves
 * (see LinkRate). Game thread only, but for the Transports', which come back to it.
 * <p>
 * A client has a seat in each room it is in: its own, which it hosts, and every host's it joined, each with a session
 * of its own. One session for all of them left a player sharing a disc with nothing of anyone else's: joining
 * another's room closed their own, and with it every viewer's connection.
 * <p>
 * Each host or join is a numbered request, and its ticket, session and leave carry that number: whatever comes
 * for a request this client no longer wants is dropped, and the server ignores a leave for one it replaced.
 */
public final class P2pClient {
    /** This client's seat in one host's room. */
    private static final class Seat {
        /** The latest host or join for it; 0 once left. */
        volatile int wanted;
        /** The request whose ticket opened {@link #transport} (or is opening it); 0 if none. */
        volatile int session;
        volatile Transport transport;
    }

    /** By the room's host. Changed on the game thread; read from any. */
    private static final Map<UUID, Seat> SEATS = new ConcurrentHashMap<>();
    private static volatile Transport.Listener media;
    private static BiConsumer<UUID, Transport> changed = (host, session) -> {};
    /** Numbers are never reused, not even on another server. */
    private static int requests;
    /** The game thread, the server, its API, and how a ticket's session opens there; other in tests. */
    static Executor game = task -> Minecraft.getInstance().execute(task);
    static Consumer<CustomPacketPayload> server = PacketDistributor::sendToServer;
    static Supplier<String> api = ServerConfig.API_URL::get;
    static Opener open = (api, ticket, listener) -> {
        try {
            Transport.loadNatives(ClientFiles.DIR.resolve("webrtc"));
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

    public static void register(IEventBus modBus) {
        P2pOverlay.register(modBus);
        NeoForge.EVENT_BUS.addListener(P2pClient::registerCommands);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> logout());
    }

    /** This client's session in {@code host}'s room (its own, if it is the host), for what sends or receives over it; null if none. */
    public static Transport session(UUID host) {
        Seat seat = SEATS.get(host);
        return seat == null ? null : seat.transport;
    }

    /** Every session this client has open. */
    public static List<Transport> sessions() {
        return SEATS.values().stream().map(seat -> seat.transport).filter(Objects::nonNull).toList();
    }

    public static void media(Transport.Listener listener) {
        media = listener;
    }

    /** {@code listener} is told of each room's session as it opens, and with null as it closes. */
    public static void changed(BiConsumer<UUID, Transport> listener) {
        changed = listener;
    }

    /** Whether this client is in {@code owner}'s room, or has now asked to be (to host it, if it is its own). */
    public static boolean connect(UUID owner) {
        Seat seat = SEATS.get(owner);
        if (seat != null && (seat.transport == null || seat.transport.state() != Transport.State.CLOSED)) {
            // Open, opening or asked for.
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
        return request(host ? P2p.Action.HOST : P2p.Action.JOIN, owner, host ? "" : player.getProfile().getName()) != 0;
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        // A client command under the server's /cms: NeoForge hands what no client command matches (/cms config) to the server.
        event.getDispatcher().register(Commands.literal(CreateMediaSystems.COMMAND).then(Commands.literal("p2p")
                .then(Commands.literal("host").executes(c -> request(P2p.Action.HOST, Minecraft.getInstance().player.getUUID(), "")))
                .then(Commands.literal("join").then(Commands.argument("host", StringArgumentType.word())
                        .suggests((c, builder) -> SharedSuggestionProvider.suggest(players(), builder))
                        .executes(c -> join(StringArgumentType.getString(c, "host")))))
                .then(Commands.literal("leave").executes(c -> leave()))
                .then(Commands.literal("status").executes(c -> status()))
                .then(Commands.literal("debug").executes(c -> {
                    say(Component.translatable(P2pOverlay.toggle() ? "create_media_systems.p2p.debug.on"
                            : "create_media_systems.p2p.debug.off"), false);
                    return 1;
                }))
                .then(Commands.literal("ping").executes(c -> {
                    List<Transport> open = sessions();
                    if (open.isEmpty()) {
                        say(Component.translatable("create_media_systems.p2p.none"), true);
                        return 0;
                    }
                    open.forEach(Transport::ping);
                    return 1;
                }))));
    }

    private static int join(String name) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo host = connection == null ? null : connection.getPlayerInfo(name);
        if (host == null) {
            say(Component.translatable("create_media_systems.p2p.no_room", name), true);
            return 0;
        }
        return request(P2p.Action.JOIN, host.getProfile().getId(), host.getProfile().getName());
    }

    /**
     * Asks the server to host, or to join {@code host}'s room, {@code name}'s. That room's session stays until the
     * server ends it for this request, so a refused one (cooldown, no such room) keeps it.
     */
    static int request(P2p.Action action, UUID host, String name) {
        if (name.length() > P2p.MAX_HOST_NAME) {
            say(Component.translatable("create_media_systems.p2p.no_room", name), true);
            return 0;
        }
        Seat seat = SEATS.computeIfAbsent(host, h -> new Seat());
        seat.wanted = ++requests;
        server.accept(new P2p.Request(action, seat.wanted, name));
        say(Component.translatable("create_media_systems.p2p.requested"), false);
        return 1;
    }

    /** Leaves every room and drops the requests in flight, whichever of them the server has as this player's. */
    static int leave() {
        SEATS.forEach((host, seat) -> {
            int open = seat.session;
            close(host, seat);
            if (open != 0) {
                server.accept(new P2p.Request(P2p.Action.LEAVE, open, ""));
            }
            if (seat.wanted != 0 && seat.wanted != open) {
                server.accept(new P2p.Request(P2p.Action.LEAVE, seat.wanted, ""));
            }
            seat.wanted = 0;
        });
        SEATS.clear();
        say(Component.translatable("create_media_systems.p2p.left"), false);
        return 1;
    }

    static void logout() {
        SEATS.forEach((host, seat) -> {
            close(host, seat);
            seat.wanted = 0;
        });
        SEATS.clear();
    }

    private static int status() {
        List<Transport> open = sessions();
        if (open.isEmpty()) {
            say(Component.translatable("create_media_systems.p2p.none"), false);
        }
        for (Transport t : open) {
            say(Component.translatable("create_media_systems.p2p.status." + (t.host() ? "host" : "viewer"),
                    t.state().name().toLowerCase(Locale.ROOT), t.connected().size(), mbps(t.budgetBps()), t.received()), false);
        }
        return 1;
    }

    /** The server's ticket for this player: opens its room's session in place of the one before, if its request is still wanted. */
    static void ticket(P2p.Ticket ticket) {
        int request = ticket.request();
        UUID host = ticket.ticket().hostId();
        Seat seat = SEATS.get(host);
        if (seat == null || request != seat.wanted) {
            // Left or asked again since: the server revoked it.
            return;
        }
        close(host, seat);
        seat.session = request;
        // The API of the server that gave the ticket: it goes nowhere else, even if this client moves on while it opens.
        String origin = api.get();
        Transport.Listener listener = new Transport.Listener() {
            @Override
            public void data(UUID from, byte[] payload) {
                Transport.Listener receiver = media;
                if (seat.session == request && receiver != null) {
                    receiver.data(from, payload);
                }
            }

            @Override
            public void control(UUID from, byte[] payload) {
                Transport.Listener receiver = media;
                if (seat.session == request && receiver != null) {
                    receiver.control(from, payload);
                }
            }

            @Override
            public void status(String message) {
                game.execute(() -> {
                    if (seat.session != request) {
                        return;
                    }
                    say(Component.literal(named(message)), false);
                    // A session that ended by itself frees this player's seat on the server too.
                    if (seat.transport != null && seat.transport.state() == Transport.State.CLOSED) {
                        ended(host, seat, request);
                    }
                });
            }
        };
        CompletableFuture.supplyAsync(() -> open.open(origin, ticket.ticket(), listener)).whenComplete((opened, error) -> game.execute(() -> {
            if (seat.session != request) {
                // Left, logged out or replaced while it opened.
                if (opened != null) {
                    opened.close();
                }
            } else if (error != null) {
                say(Component.translatable("create_media_systems.p2p.natives_failed", ApiHttp.reason(error)), true);
                ended(host, seat, request);
            } else if (opened.state() == Transport.State.CLOSED) {
                ended(host, seat, request);
            } else {
                seat.transport = opened;
                changed.accept(host, opened);
            }
        }));
    }

    /** Request {@code request}'s session is over: the server frees this player's seat, unless it has a later request for it. */
    private static void ended(UUID host, Seat seat, int request) {
        close(host, seat);
        if (seat.wanted == request) {
            seat.wanted = 0;
            SEATS.remove(host, seat);
        }
        server.accept(new P2p.Request(P2p.Action.LEAVE, request, ""));
    }

    /** The server refused request {@code request}: its room keeps the session it had, or is asked for no longer. */
    static void notice(Component message, int request) {
        SEATS.forEach((host, seat) -> {
            if (seat.wanted == request && seat.session != request) {
                seat.wanted = seat.session;
                if (seat.session == 0) {
                    SEATS.remove(host, seat);
                }
            }
        });
        say(message, true);
    }

    private static void close(UUID host, Seat seat) {
        seat.session = 0;
        Transport open = seat.transport;
        if (open != null) {
            open.close();
            seat.transport = null;
            changed.accept(host, null);
        }
    }

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

    static String mbps(long bps) {
        return String.format(Locale.ROOT, "%.1f", bps / 1e6);
    }

    private static void say(Component message, boolean failure) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(failure ? message.copy().withStyle(ChatFormatting.RED) : message, false);
        }
    }
}
