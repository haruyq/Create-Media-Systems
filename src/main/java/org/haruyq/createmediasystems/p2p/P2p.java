package org.haruyq.createmediasystems.p2p;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ServerConfig;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Direct P2P sessions between players (see README). A player asks this server to host or join; the server asks the
 * API, with a secret only it and the API know, for a ticket for that player alone, and sends it to them. Peers then
 * signal through the API and send data to each other directly (see Transport): nothing of theirs passes through
 * here. The secret is CMS_P2P_SERVER_TOKEN, else the server config's p2pServerToken, which players never receive.
 * <p>
 * A player hosts one room and may sit in any number of others at once: two players each sharing a disc watch each
 * other's. One seat a player closed their own room to join another's, which dropped everyone watching theirs.
 */
public final class P2p {
    /** Longest name of a host to join: a player's. */
    static final int MAX_HOST_NAME = 16;
    private static final String TOKEN_ENV = "CMS_P2P_SERVER_TOKEN";
    private static final long COOLDOWN_MS = 3000;
    // Never follows redirects, so the secret goes to the configured API only. HTTP/1.1: uvicorn refuses h2c upgrades.
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final Object LOCK = new Object();
    /** Under LOCK. Rooms by host, and each player's latest request for each seat, with the room it got them once answered. */
    private static final Map<UUID, Room> ROOMS = new HashMap<>();
    private static final Map<Seat, Member> MEMBERS = new HashMap<>();
    /**
     * Under LOCK. Each player's API calls, one after another, each with the cleanup of its answer: a DELETE for what
     * they left never lands on what a later POST got them, nor a POST on a seat that is still being deleted.
     */
    private static final Map<UUID, CompletableFuture<?>> CALLS = new HashMap<>();
    private static boolean stopping;
    /** Server thread only. */
    private static final Map<Seat, Long> LAST_REQUEST = new HashMap<>();
    /** The API: {@link #http} but in tests. */
    static Api api = P2p::http;

    private record Room(String id, UUID host) {
    }

    /** {@code player}'s place in {@code host}'s room: their own, which they host, or another's. */
    private record Seat(UUID player, UUID host) {
        boolean hosts() {
            return player.equals(host);
        }
    }

    /** A player's request {@code id} for a seat (as their client numbered it), and the room it got them once the API answered. */
    private static final class Member {
        final int request;
        Room room;

        Member(int request) {
            this.request = request;
        }
    }

    /** Who asked: whether they have left the server since, and how to send them something. */
    record Player(UUID id, BooleanSupplier gone, Consumer<CustomPacketPayload> send) {
    }

    record Answer(int status, String body) {
    }

    interface Api {
        CompletableFuture<Answer> call(String method, String path, JsonObject body);
    }

    private P2p() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            logout(event.getEntity().getUUID());
            LAST_REQUEST.keySet().removeIf(seat -> seat.player().equals(event.getEntity().getUUID()));
        });
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> start());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> stop());
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Request.TYPE, Request.CODEC,
                (payload, context) -> request((ServerPlayer) context.player(), payload));
        registrar.playToClient(Ticket.TYPE, Ticket.CODEC, (payload, context) -> P2pClient.ticket(payload));
        registrar.playToClient(Notice.TYPE, Notice.CODEC,
                (payload, context) -> P2pClient.notice(payload.message(), payload.request()));
    }

    private static void request(ServerPlayer player, Request request) {
        UUID id = player.getUUID();
        if (request.action() == Action.LEAVE) {
            leave(id, request.id());
            return;
        }
        ServerPlayer host = request.action() == Action.JOIN ? player.server.getPlayerList().getPlayerByName(request.host()) : null;
        if (request.action() == Action.JOIN && host == null) {
            notice(player, request, Component.translatable("create_media_systems.p2p.no_room", request.host()));
            return;
        }
        // Each seat by itself: a player near two discs asks for both rooms at once.
        Seat seat = new Seat(id, host == null ? id : host.getUUID());
        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST.getOrDefault(seat, 0L) < COOLDOWN_MS) {
            notice(player, request, Component.translatable("create_media_systems.p2p.cooldown"));
            return;
        }
        LAST_REQUEST.put(seat, now);
        if (secret() == null) {
            notice(player, request, Component.translatable("create_media_systems.p2p.disabled"));
            return;
        }
        request(new Player(id, player::hasDisconnected, payload -> player.server.execute(
                () -> PacketDistributor.sendToPlayer(player, payload))), request, host == null ? null : host.getUUID());
    }

    /**
     * Asks the API for {@code player}'s ticket to host, or to join {@code host}'s room, in place of the seat they had
     * there: their seats in other rooms stay.
     */
    static void request(Player player, Request request, UUID host) {
        synchronized (LOCK) {
            Room room = host == null ? null : ROOMS.get(host);
            if (stopping || host != null && (room == null || host.equals(player.id()))) {
                player.send().accept(new Notice(Component.translatable("create_media_systems.p2p.no_room", request.host()),
                        request.id()));
                return;
            }
            Seat seat = new Seat(player.id(), host == null ? player.id() : host);
            end(seat);
            Member member = new Member(request.id());
            MEMBERS.put(seat, member);
            JsonObject body = new JsonObject();
            body.addProperty(room == null ? "host_id" : "peer_id", player.id().toString());
            String path = room == null ? "p2p/sessions" : "p2p/sessions/" + room.id() + "/peers";
            then(player.id(), () -> api.call("POST", path, body).handle((answer, error) -> {
                synchronized (LOCK) {
                    return answered(player, seat, member, room, answer, error);
                }
            }).thenCompose(cleanup -> cleanup));
        }
    }

    /**
     * Under LOCK. Gives {@code player} the ticket the API answered for {@code member} (their request to host, or to
     * join {@code room}) if that request still stands, else revokes it; done when the returned future is.
     */
    private static CompletableFuture<?> answered(Player player, Seat seat, Member member, Room room, Answer answer,
                                                 Throwable error) {
        Protocol.Ticket ticket = error == null ? ticketFrom(answer, player.id(), room) : null;
        // Still what the player waits for, and whether the room they asked to join still is.
        boolean wanted = MEMBERS.get(seat) == member && !player.gone().getAsBoolean() && !stopping;
        boolean live = room == null || ROOMS.get(room.host()) == room;
        String reason = null;
        if (ticket == null) {
            reason = error != null ? ApiHttp.reason(error) : ApiHttp.errorDetail(answer.status(), answer.body());
            LogUtils.getLogger().warn("Could not get a P2P ticket for {}: {}", player.id(), reason);
            if (room != null && answer != null && answer.status() == 404) {
                close(room);
            }
        }
        if (wanted && (ticket == null || !live)) {
            MEMBERS.remove(seat, member);
            player.send().accept(new Notice(ticket == null
                    ? Component.translatable("create_media_systems.p2p.api_failed", reason)
                    : Component.translatable("create_media_systems.p2p.host_left"), member.request));
        }
        if (ticket == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!wanted || !live) {
            // Left, asked again, logged out, stopping or the host left meanwhile: the ticket is not wanted.
            return delete(room == null ? "p2p/sessions/" + ticket.sessionId()
                    : "p2p/sessions/" + room.id() + "/peers/" + player.id());
        }
        member.room = room == null ? new Room(ticket.sessionId(), player.id()) : room;
        if (room == null) {
            ROOMS.put(player.id(), member.room);
        }
        player.send().accept(new Ticket(ticket, member.request));
        return CompletableFuture.completedFuture(null);
    }

    /** The ticket the API answered for {@code player}, if it is one, for them, in {@code room} (null: hosting). */
    private static Protocol.Ticket ticketFrom(Answer answer, UUID player, Room room) {
        if (answer.status() / 100 != 2) {
            return null;
        }
        try {
            Protocol.Ticket ticket = Protocol.Ticket.parse(answer.body());
            boolean ours = ticket.peerId().equals(player) && ticket.host() == (room == null)
                    && ticket.hostId().equals(room == null ? player : room.host())
                    && (room == null || ticket.sessionId().equals(room.id()));
            return ours ? ticket : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ends {@code player}'s request {@code request}, unless a later one for its seat replaced it. */
    static void leave(UUID player, int request) {
        synchronized (LOCK) {
            for (Seat seat : List.copyOf(MEMBERS.keySet())) {
                Member member = MEMBERS.get(seat);
                if (seat.player().equals(player) && member != null && member.request == request) {
                    end(seat);
                }
            }
        }
    }

    static void logout(UUID player) {
        synchronized (LOCK) {
            for (Seat seat : List.copyOf(MEMBERS.keySet())) {
                if (seat.player().equals(player)) {
                    end(seat);
                }
            }
        }
    }

    /** Under LOCK. Takes a player out of {@code seat}'s room, or drops their pending request for it; a host's room closes. */
    private static void end(Seat seat) {
        Member member = MEMBERS.remove(seat);
        if (member == null || member.room == null) {
            return;
        }
        Room room = member.room;
        if (seat.hosts()) {
            close(room);
        } else {
            then(seat.player(), () -> delete("p2p/sessions/" + room.id() + "/peers/" + seat.player()));
        }
    }

    /** Under LOCK. */
    private static void close(Room room) {
        if (ROOMS.remove(room.host(), room)) {
            MEMBERS.values().removeIf(member -> member.room == room);
            then(room.host(), () -> delete("p2p/sessions/" + room.id()));
        }
    }

    /** Under LOCK. Runs {@code call} once {@code player}'s earlier calls are done, whichever way they went. */
    private static void then(UUID player, Supplier<CompletableFuture<?>> call) {
        CompletableFuture<?> next = CALLS.getOrDefault(player, CompletableFuture.completedFuture(null))
                .handle((v, e) -> null).thenCompose(v -> call.get());
        CALLS.put(player, next);
        next.whenComplete((v, e) -> {
            synchronized (LOCK) {
                CALLS.remove(player, next);
            }
        });
    }

    private static CompletableFuture<?> delete(String path) {
        return api.call("DELETE", path, null).whenComplete((answer, error) -> {
            if (error != null || answer.status() / 100 != 2 && answer.status() != 404) {
                LogUtils.getLogger().warn("Could not revoke a P2P session or peer: {}",
                        error != null ? ApiHttp.reason(error) : answer.status());
            }
        });
    }

    static void start() {
        synchronized (LOCK) {
            stopping = false;
        }
    }

    /** Closes every room and waits (at most 5 s) for every call, the revocation of late answers included. */
    static void stop() {
        CompletableFuture<?> calls;
        synchronized (LOCK) {
            stopping = true;
            List.copyOf(ROOMS.values()).forEach(P2p::close);
            MEMBERS.clear();
            calls = CompletableFuture.allOf(CALLS.values().toArray(CompletableFuture[]::new));
        }
        LAST_REQUEST.clear();
        try {
            calls.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LogUtils.getLogger().warn("Could not revoke every P2P session before stopping: {}", ApiHttp.reason(e));
        }
    }

    private static CompletableFuture<Answer> http(String method, String path, JsonObject body) {
        String secret = secret();
        if (secret == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("P2P is off on this server"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(Protocol.endpoint(ServerConfig.API_URL.get(), path, false))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> new Answer(response.statusCode(), response.body()));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** The server's own P2P secret, null if it has none (P2P is off). */
    private static String secret() {
        String secret = System.getenv(TOKEN_ENV);
        if (secret == null || secret.isBlank()) {
            secret = P2pFeature.SERVER_TOKEN.get();
        }
        return Protocol.TOKEN.matcher(secret.strip()).matches() ? secret.strip() : null;
    }

    private static void notice(ServerPlayer player, Request request, Component message) {
        PacketDistributor.sendToPlayer(player, new Notice(message, request.id()));
    }

    public enum Action {
        HOST, JOIN, LEAVE
    }

    /**
     * Client → server: request {@code id} (numbered by the client) to host a room or join {@code host}'s; or to leave
     * what request {@code id} got.
     */
    public record Request(Action action, int id, String host) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(CreateMediaSystems.id("p2p_request"));
        static final StreamCodec<ByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), Request::action,
                ByteBufCodecs.VAR_INT, Request::id,
                ByteBufCodecs.stringUtf8(MAX_HOST_NAME), Request::host,
                Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** Server → client: this player's own ticket to the API's signaling, for their request {@code request}. */
    public record Ticket(Protocol.Ticket ticket, int request) implements CustomPacketPayload {
        public static final Type<Ticket> TYPE = new Type<>(CreateMediaSystems.id("p2p_ticket"));
        static final StreamCodec<ByteBuf, Ticket> CODEC = NeoForgeStreamCodecs.composite(
                ByteBufCodecs.stringUtf8(Protocol.MAX_SESSION_ID), t -> t.ticket().sessionId(),
                UUIDUtil.STREAM_CODEC, t -> t.ticket().peerId(),
                UUIDUtil.STREAM_CODEC, t -> t.ticket().hostId(),
                ByteBufCodecs.BOOL, t -> t.ticket().host(),
                ByteBufCodecs.stringUtf8(Protocol.MAX_TOKEN), t -> t.ticket().token(),
                ByteBufCodecs.stringUtf8(Protocol.MAX_STUN_URL).apply(ByteBufCodecs.list(Protocol.MAX_STUN_URLS)),
                t -> t.ticket().stunUrls(),
                ByteBufCodecs.VAR_INT, Ticket::request,
                (sessionId, peerId, hostId, host, token, stunUrls, request) ->
                        new Ticket(new Protocol.Ticket(sessionId, peerId, hostId, host, token, stunUrls), request));

        @Override
        public Type<Ticket> type() {
            return TYPE;
        }
    }

    /** Server → client: why their request {@code request} failed. */
    public record Notice(Component message, int request) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = new Type<>(CreateMediaSystems.id("p2p_notice"));
        static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.composite(
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notice::message,
                ByteBufCodecs.VAR_INT, Notice::request,
                Notice::new);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }
}
