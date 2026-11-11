package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.BlockIdentity;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the lighting analysis: a session per source a console follows, whose owner (one of its listeners,
 * see AnalysisLease) uploads the signal it plays, which the server keeps and hands every viewer (see
 * AnalysisPlayback); and each console's signal, sampled every tick.
 */
public final class AnalysisCoordinator {
    private static final Map<ServerLevel, Map<UUID, Session>> LEVELS = new HashMap<>();
    /** How near a console a player sees its lights: as far as a fixture it links is drawn from. */
    private static final double SEEN = LightingSettings.VIEW_DISTANCE + Link.MAX_DISTANCE;
    /** A prepared batch a viewer has not said it received is sent again after this long (ns). */
    private static final long RESEND_NANOS = 5_000_000_000L;

    private AnalysisCoordinator() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> LEVELS.clear());
    }

    private static final class Audience {
        final Map<String, Long> sent = new HashMap<>();
        AnalysisPackets.Batch pending;
        long pendingAt;
        long statusAt = System.nanoTime() / 1_000_000 - AnalysisSession.STATUS_MIN_MILLIS;
    }

    static final class Session extends AnalysisSession {
        final UUID source;
        UUID id = UUID.randomUUID();
        AnalysisLease lease = new AnalysisLease();
        final Map<UUID, Long> ready = new HashMap<>();
        final Map<UUID, Audience> audience = new HashMap<>();
        final Set<UUID> eligible = new HashSet<>();
        final Set<BlockPos> consoles = new HashSet<>();
        BlockPos pos;
        /** The load of its source's track it follows (see Streams#loadId): a new one starts it over. */
        long load;
        UUID preferred;
        int height;
        long millis;
        long touched;
        long sentAt = -100;
        long receivedAt = -100;
        long liveTokenAt;
        int liveTokens = 20;
        long clockAt;
        long clockMillis;
        boolean active;

        Session(UUID source, BlockPos pos) {
            this.source = source;
            this.pos = pos;
        }

        AnalysisPackets.State state() {
            return new AnalysisPackets.State(source, id, lease.generation,
                    lease.owner == null ? AnalysisPackets.NONE : lease.owner, pos, track, next, height, position(), active,
                    delay);
        }

        long position() {
            return clockAt == 0 ? millis : clockMillis + (System.nanoTime() - clockAt) / 1_000_000;
        }

        boolean hasLiveToken(long tick) {
            liveTokens = (int) Math.min(20, liveTokens + Math.max(0, tick - liveTokenAt));
            liveTokenAt = tick;
            return liveTokens > 0;
        }

        @Override
        void restart() {
            super.restart();
            id = UUID.randomUUID();
            lease = new AnalysisLease();
            ready.clear();
            audience.values().forEach(value -> {
                value.sent.clear();
                value.pending = null;
            });
            sentAt = -100;
            clockAt = 0;
        }
    }

    /** What the server keeps of a source's session, for the development probes. */
    public record Snapshot(UUID source, UUID session, String track, String next, int generation, @Nullable UUID owner,
                           long sequence, long liveSequence, long published, int ready, long trackEnd, long nextEnd) {
    }

    public static List<Snapshot> snapshots() {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Map<UUID, Session> sessions : LEVELS.values()) {
            for (Session session : sessions.values()) {
                AnalysisTimeline track = session.tracks.get(session.track);
                AnalysisTimeline next = session.tracks.get(session.next);
                snapshots.add(new Snapshot(session.source, session.id, session.track, session.next,
                        session.lease.generation, session.lease.owner, session.lease.sequence,
                        session.lease.liveSequence, session.lease.published, session.ready.size(),
                        track == null ? -1 : track.end(), next == null ? -1 : next.end()));
            }
        }
        return snapshots;
    }

    @Nullable
    static Session session(ServerLevel level, UUID source) {
        Map<UUID, Session> sessions = LEVELS.get(level);
        return sessions == null ? null : sessions.get(source);
    }

    public static void use(LightingConsoleBlock.Entity console) {
        if (!(console.getLevel() instanceof ServerLevel level)) {
            return;
        }
        use(level, console.audioSource(), console.getBlockPos());
        use(level, console.videoSource(), console.getBlockPos());
        console.receive(sample(level, console.audioSource()), sample(level, console.videoSource()), level.getGameTime());
    }

    @Nullable
    private static AnalysisFrame sample(ServerLevel level, @Nullable BlockPos pos) {
        UUID source = pos == null ? null : BlockIdentity.of(level, pos);
        Session session = source == null ? null : session(level, source);
        return session == null || !session.active ? null : session.sample(session.position());
    }

    private static void use(ServerLevel level, BlockPos pos, BlockPos console) {
        if (pos == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof MediaSource media)) {
            return;
        }
        UUID id = BlockIdentity.of(level, pos);
        if (id == null) {
            return;
        }
        Map<UUID, Session> sessions = LEVELS.computeIfAbsent(level, ignored -> new HashMap<>());
        if (!sessions.containsKey(id) && sessions.size() >= AnalysisSession.MAX_SESSIONS) {
            return;
        }
        BlockEntity source = (BlockEntity) media;
        Session session = sessions.computeIfAbsent(id, ignored -> new Session(id, pos.immutable()));
        if (session.load != Streams.loadId(source)) {
            session.load = Streams.loadId(source);
            session.restart();
        }
        session.pos = pos.immutable();
        session.consoles.add(console.immutable());
        session.touched = level.getGameTime();
        session.preferred = Streams.startedBy(source);
        Streams.Source playing = Streams.source(level, pos);
        // Not while its track loads: its position stands at 0 then, and clients, running it on from each state they
        // are sent, would play the lights the track's first half second over and over until it starts.
        boolean active = playing != null && Streams.loaded(source);
        if (active && (!playing.url().equals(session.track) || !session.active)) {
            session.restart();
        }
        session.active = active;
        if (active) {
            session.follow(playing.url(), playing.video() == null ? "" : playing.video().next().orElse(""));
            session.height = playing.video() == null ? 0 : playing.video().maxHeight();
            session.millis = playing.ticks() * PlaybackClock.NANOS_PER_TICK / 1_000_000;
        }
    }

    private static void tick() {
        long now = System.nanoTime() / 1_000_000;
        Map<UUID, Integer> batches = new HashMap<>();
        for (var levelEntry : LEVELS.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            var sessions = new ArrayList<>(levelEntry.getValue().values());
            Collections.rotate(sessions, sessions.isEmpty() ? 0 : (int) (level.getGameTime() / 2 % sessions.size()));
            for (Session session : sessions) {
                if (level.getGameTime() - session.touched > 40) {
                    session.active = false;
                    for (ServerPlayer player : level.players()) {
                        if (session.audience.containsKey(player.getUUID())) {
                            PacketDistributor.sendToPlayer(player, session.state());
                        }
                    }
                    levelEntry.getValue().remove(session.source);
                    continue;
                }
                if (level.getGameTime() % 2 != 0) {
                    continue;
                }
                Set<ServerPlayer> listeners = Streams.listeners(level, session.pos);
                session.eligible.clear();
                listeners.forEach(player -> session.eligible.add(player.getUUID()));
                session.ready.keySet().retainAll(session.eligible);
                if (!session.active) {
                    session.ready.clear();
                }
                boolean changed = session.lease.elect(session.ready, session.preferred, now);
                Set<UUID> present = new HashSet<>();
                session.consoles.removeIf(pos -> !level.isLoaded(pos)
                        || !(level.getBlockEntity(pos) instanceof LightingConsoleBlock.Entity console)
                        || !console.formed || console.controller() != console);
                for (ServerPlayer player : level.players()) {
                    boolean nearby = listeners.contains(player) || session.consoles.stream().anyMatch(pos ->
                            player.distanceToSqr(SableCompat.toWorld(level, pos, pos.getCenter())) <= SEEN * SEEN);
                    if (!nearby) {
                        continue;
                    }
                    present.add(player.getUUID());
                    boolean joined = !session.audience.containsKey(player.getUUID());
                    Audience audience = session.audience.computeIfAbsent(player.getUUID(), ignored -> new Audience());
                    if (joined || changed || level.getGameTime() - session.sentAt >= AnalysisSession.STATE_TICKS) {
                        PacketDistributor.sendToPlayer(player, session.state());
                    }
                    if (session.active && batches.getOrDefault(player.getUUID(), 0) < 2 && replay(player, session, audience)) {
                        batches.merge(player.getUUID(), 1, Integer::sum);
                    }
                }
                session.audience.keySet().retainAll(present);
                if (changed || level.getGameTime() - session.sentAt >= AnalysisSession.STATE_TICKS) {
                    session.sentAt = level.getGameTime();
                }
                session.consoles.clear();
            }
        }
    }

    private static boolean replay(ServerPlayer player, Session session, Audience audience) {
        if (audience.pending != null) {
            long now = System.nanoTime();
            if (now - audience.pendingAt > RESEND_NANOS) {
                PacketDistributor.sendToPlayer(player, audience.pending);
                audience.pendingAt = now;
                return true;
            }
            return false;
        }
        for (String track : session.both()) {
            AnalysisTimeline timeline = session.tracks.get(track);
            if (timeline == null || !timeline.prepared()) {
                continue;
            }
            long cursor = audience.sent.getOrDefault(track, track.equals(session.track) ? Math.max(-1, session.position() - 1000) : -1);
            List<AnalysisFrame> frames = timeline.after(cursor, AnalysisPackets.BATCH_SIZE);
            if (!frames.isEmpty()) {
                audience.pending = new AnalysisPackets.Batch(session.source, session.id, track, true, frames);
                audience.pendingAt = System.nanoTime();
                PacketDistributor.sendToPlayer(player, audience.pending);
                return true;
            }
        }
        return false;
    }

    public static void received(ServerPlayer player, AnalysisPackets.Received received) {
        Session session = session(player, received.source(), received.session());
        Audience audience = session == null ? null : session.audience.get(player.getUUID());
        AnalysisPackets.Batch pending = audience == null ? null : audience.pending;
        if (pending != null && pending.track().equals(received.track())
                && pending.frames().getLast().millis() == received.millis()) {
            audience.sent.put(received.track(), received.millis());
            audience.pending = null;
        }
    }

    @Nullable
    private static Session session(ServerPlayer player, UUID source, UUID id) {
        Session session = session(player.serverLevel(), source);
        return session != null && session.id.equals(id) && session.active ? session : null;
    }

    public static void status(ServerPlayer player, AnalysisPackets.Status status) {
        Session session = session(player, status.source(), status.session());
        if (session == null || status.millis() < 0 || status.millis() > AnalysisFrame.MAX_MILLIS
                || status.upstreamMillis() < 0 || status.upstreamMillis() > AnalysisDelay.MAX_MILLIS) {
            return;
        }
        UUID id = player.getUUID();
        Audience audience = session.audience.get(id);
        boolean eligible = session.eligible.contains(id);
        long now = System.nanoTime() / 1_000_000;
        if (audience == null ? !eligible : now - audience.statusAt < AnalysisSession.STATUS_MIN_MILLIS) {
            return;
        }
        if (audience != null) {
            audience.statusAt = now;
        }
        if (eligible && status.ready()) {
            session.ready.put(id, now);
            if (id.equals(session.lease.owner)) {
                session.clockMillis = status.millis() + status.upstreamMillis();
                session.clockAt = System.nanoTime();
            }
            AnalysisDelay delay = session.delay.increase(status.lagMillis(), session.position());
            if (!delay.equals(session.delay)) {
                session.delay = delay;
                session.sentAt = -100;
            }
            if (id.equals(session.lease.owner) && session.sample(status.millis()) != null) {
                session.lease.published = now;
            }
        } else if (eligible) {
            session.ready.remove(id);
        }
        if (audience != null && status.clientSentNanos() != 0) {
            PacketDistributor.sendToPlayer(player, new AnalysisPackets.Clock(session.source, session.id,
                    status.clientSentNanos(), session.clockAt == 0 ? -1 : session.position()));
        }
    }

    public static void upload(ServerPlayer player, AnalysisPackets.Upload upload) {
        Session session = session(player, upload.source(), upload.session());
        long tick = player.level().getGameTime();
        if (upload.prepared() && session != null && player.getUUID().equals(session.lease.owner)
                && upload.generation() == session.lease.generation && upload.sequence() == session.lease.sequence) {
            PacketDistributor.sendToPlayer(player, new AnalysisPackets.Accepted(session.source, session.id,
                    session.lease.generation, upload.sequence()));
            return;
        }
        if (session == null || !session.eligible.contains(player.getUUID())
                || !session.lease.accepts(player.getUUID(), upload.generation(), upload.sequence(), upload.prepared())
                || upload.prepared() && tick - session.receivedAt < 1 || upload.frames().isEmpty()
                || !session.has(upload.track())
                || !upload.prepared() && (!upload.track().equals(session.track) || upload.frames().size() != 1
                        || !session.hasLiveToken(tick))) {
            return;
        }
        AnalysisTimeline timeline = session.timeline(upload.track());
        if (!(upload.prepared() ? timeline.publishPrepared(upload.frames()) : timeline.add(upload.frames(), false))) {
            return;
        }
        session.lease.accepted(upload.sequence(), upload.prepared(), System.nanoTime() / 1_000_000);
        if (upload.prepared()) {
            session.receivedAt = tick;
            PacketDistributor.sendToPlayer(player, new AnalysisPackets.Accepted(session.source, session.id,
                    session.lease.generation, upload.sequence()));
        } else {
            session.liveTokens--;
        }
        if (!upload.prepared()) {
            for (ServerPlayer listener : player.serverLevel().players()) {
                if (session.audience.containsKey(listener.getUUID())) {
                    PacketDistributor.sendToPlayer(listener, new AnalysisPackets.Batch(session.source, session.id,
                            session.track, false, upload.frames()));
                }
            }
        }
    }
}
