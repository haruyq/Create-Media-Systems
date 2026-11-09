package org.haruyq.createmediasystems.p2p;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Pattern;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.stream.UrlPolicy;

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
import net.neoforged.fml.loading.FMLPaths;
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
 * here. The secret is CMS_P2P_SERVER_TOKEN or config/cms-p2p-server-token.txt, never the synced server config.
 */
public final class P2p {
    private static final Pattern TOKEN = Pattern.compile("[\\x21-\\x7e]{16,512}");
    private static final String TOKEN_ENV = "CMS_P2P_SERVER_TOKEN";
    private static final String TOKEN_FILE = "cms-p2p-server-token.txt";
    private static final long COOLDOWN_MS = 3000;
    // Never follows redirects, so the secret goes to the configured API only. HTTP/1.1: uvicorn refuses h2c upgrades.
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final Object LOCK = new Object();
    /** Under LOCK. Rooms by host, and each player's latest request, with the room it got them once answered. */
    private static final Map<UUID, Room> ROOMS = new HashMap<>();
    private static final Map<UUID, Member> MEMBERS = new HashMap<>();
    /**
     * Under LOCK. Each player's API calls, one after another, each with the cleanup of its answer: a DELETE for what
     * they left never lands on what a later POST got them, nor a POST on a seat that is still being deleted.
     */
    private static final Map<UUID, CompletableFuture<?>> CALLS = new HashMap<>();
    private static boolean stopping;
    /** Server thread only. */
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    /** The API: {@link #http} but in tests. */
    static Api api = P2p::http;

    private record Room(String id, UUID host) {
    }

    /** A player's request {@code id} (as their client numbered it), and the room it got them once the API answered. */
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
            LAST_REQUEST.remove(event.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> start());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> stop());
    }

    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(Request.TYPE, Request.CODEC,
                (payload, context) -> request((ServerPlayer) context.player(), payload));
        registrar.playToClient(Ticket.TYPE, Ticket.CODEC, (payload, context) -> P2pClient.ticket(payload));
        registrar.playToClient(Notice.TYPE, Notice.CODEC, (payload, context) -> P2pClient.notice(payload.message()));
    }

    private static void request(ServerPlayer player, Request request) {
        UUID id = player.getUUID();
        if (request.action() == Action.LEAVE) {
            leave(id, request.id());
            return;
        }
        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST.getOrDefault(id, 0L) < COOLDOWN_MS) {
            notice(player, Component.translatable("create_media_systems.p2p.cooldown"));
            return;
        }
        LAST_REQUEST.put(id, now);
        if (secret() == null) {
            notice(player, Component.translatable("create_media_systems.p2p.disabled"));
            return;
        }
        if (!Protocol.validBps(request.uploadBps()) || !Protocol.validBps(request.downloadBps())) {
            notice(player, Component.translatable("create_media_systems.p2p.speedtest.required"));
            return;
        }
        ServerPlayer host = request.action() == Action.JOIN ? player.server.getPlayerList().getPlayerByName(request.host()) : null;
        if (request.action() == Action.JOIN && host == null) {
            notice(player, Component.translatable("create_media_systems.p2p.no_room", request.host()));
            return;
        }
        request(new Player(id, player::hasDisconnected, payload -> player.server.execute(
                () -> PacketDistributor.sendToPlayer(player, payload))), request, host == null ? null : host.getUUID());
    }

    /** Asks the API for {@code player}'s ticket to host, or to join {@code host}'s room, in place of what they had. */
    static void request(Player player, Request request, UUID host) {
        synchronized (LOCK) {
            Room room = host == null ? null : ROOMS.get(host);
            if (stopping || host != null && (room == null || host.equals(player.id()))) {
                player.send().accept(new Notice(Component.translatable("create_media_systems.p2p.no_room", request.host())));
                return;
            }
            end(player.id());
            Member member = new Member(request.id());
            MEMBERS.put(player.id(), member);
            JsonObject body = new JsonObject();
            body.addProperty(room == null ? "host_id" : "peer_id", player.id().toString());
            body.addProperty("upload_bps", request.uploadBps());
            body.addProperty("download_bps", request.downloadBps());
            String path = room == null ? "p2p/sessions" : "p2p/sessions/" + room.id() + "/peers";
            then(player.id(), () -> api.call("POST", path, body).handle((answer, error) -> {
                synchronized (LOCK) {
                    return answered(player, member, room, answer, error);
                }
            }).thenCompose(cleanup -> cleanup));
        }
    }

    /**
     * Under LOCK. Gives {@code player} the ticket the API answered for {@code member} (their request to host, or to
     * join {@code room}) if that request still stands, else revokes it; done when the returned future is.
     */
    private static CompletableFuture<?> answered(Player player, Member member, Room room, Answer answer, Throwable error) {
        Protocol.Ticket ticket = error == null ? ticketFrom(answer, player.id(), room) : null;
        // Still what the player waits for, and whether the room they asked to join still is.
        boolean wanted = MEMBERS.get(player.id()) == member && !player.gone().getAsBoolean() && !stopping;
        boolean live = room == null || ROOMS.get(room.host()) == room;
        String reason = null;
        if (ticket == null) {
            reason = error != null ? UrlPolicy.reason(error) : UrlPolicy.errorDetail(answer.status(), answer.body());
            LogUtils.getLogger().warn("Could not get a P2P ticket for {}: {}", player.id(), reason);
            if (room != null && answer != null && answer.status() == 404) {
                close(room);
            }
        }
        if (wanted && (ticket == null || !live)) {
            MEMBERS.remove(player.id(), member);
            player.send().accept(new Notice(ticket == null
                    ? Component.translatable("create_media_systems.p2p.api_failed", reason)
                    : Component.translatable("create_media_systems.p2p.host_left")));
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

    /** Ends {@code player}'s request {@code request}, unless a later one replaced it. */
    static void leave(UUID player, int request) {
        synchronized (LOCK) {
            Member member = MEMBERS.get(player);
            if (member != null && member.request == request) {
                end(player);
            }
        }
    }

    static void logout(UUID player) {
        synchronized (LOCK) {
            end(player);
        }
    }

    /** Under LOCK. Takes {@code player} out of their room, or drops their pending request; a host's room closes. */
    private static void end(UUID player) {
        Member member = MEMBERS.remove(player);
        if (member == null || member.room == null) {
            return;
        }
        Room room = member.room;
        if (room.host().equals(player)) {
            close(room);
        } else {
            then(player, () -> delete("p2p/sessions/" + room.id() + "/peers/" + player));
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
                        error != null ? UrlPolicy.reason(error) : answer.status());
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
            LogUtils.getLogger().warn("Could not revoke every P2P session before stopping: {}", UrlPolicy.reason(e));
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
            Path file = FMLPaths.CONFIGDIR.get().resolve(TOKEN_FILE);
            try {
                secret = Files.isRegularFile(file) ? Files.readString(file) : null;
            } catch (IOException e) {
                LogUtils.getLogger().warn("Could not read {}: {}", file, e.getMessage());
                return null;
            }
        }
        return secret == null || !TOKEN.matcher(secret.strip()).matches() ? null : secret.strip();
    }

    private static void notice(ServerPlayer player, Component message) {
        PacketDistributor.sendToPlayer(player, new Notice(message));
    }

    public enum Action {
        HOST, JOIN, LEAVE
    }

    /**
     * Client → server: request {@code id} (numbered by the client) to host a room or join {@code host}'s, with this
     * client's measured bit rates; or to leave what request {@code id} got.
     */
    public record Request(Action action, int id, String host, long uploadBps, long downloadBps) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(CreateMediaSystems.id("p2p_request"));
        static final StreamCodec<ByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), Request::action,
                ByteBufCodecs.VAR_INT, Request::id,
                ByteBufCodecs.stringUtf8(16), Request::host,
                ByteBufCodecs.VAR_LONG, Request::uploadBps,
                ByteBufCodecs.VAR_LONG, Request::downloadBps,
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
                ByteBufCodecs.stringUtf8(128), t -> t.ticket().sessionId(),
                UUIDUtil.STREAM_CODEC, t -> t.ticket().peerId(),
                UUIDUtil.STREAM_CODEC, t -> t.ticket().hostId(),
                ByteBufCodecs.BOOL, t -> t.ticket().host(),
                ByteBufCodecs.stringUtf8(512), t -> t.ticket().token(),
                ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(16)), t -> t.ticket().stunUrls(),
                ByteBufCodecs.VAR_INT, Ticket::request,
                (sessionId, peerId, hostId, host, token, stunUrls, request) ->
                        new Ticket(new Protocol.Ticket(sessionId, peerId, hostId, host, token, stunUrls), request));

        @Override
        public Type<Ticket> type() {
            return TYPE;
        }
    }

    /** Server → client: why a request failed. */
    public record Notice(Component message) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = new Type<>(CreateMediaSystems.id("p2p_notice"));
        static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC =
                ComponentSerialization.TRUSTED_STREAM_CODEC.map(Notice::new, Notice::message);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }
}
