package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.SourceLinks;
import org.haruyq.createmediasystems.stream.Streams;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AnalysisCoordinator {
    private static final Map<ServerLevel, Map<UUID, Session>> LEVELS = new HashMap<>();

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
        long statusAt = System.nanoTime() / 1_000_000 - 500;
    }

    private static final class Session {
        final UUID source;
        UUID session = UUID.randomUUID();
        AnalysisLease lease = new AnalysisLease();
        final Map<UUID, Long> ready = new HashMap<>();
        final Map<UUID, Audience> audience = new HashMap<>();
        final Set<UUID> eligible = new HashSet<>();
        final Map<String, AnalysisTimeline> tracks = new LinkedHashMap<>();
        final Set<BlockPos> consoles = new HashSet<>();
        BlockPos pos;
        UUID preferred;
        String track = "";
        String next = "";
        int height;
        long millis;
        long touched;
        long sentAt = -100;
        long receivedAt = -100;
        long liveTokenAt;
        int liveTokens = 20;
        long clockAt;
        long clockMillis;
        AnalysisDelay delay = AnalysisDelay.INITIAL;
        boolean active;

        Session(UUID source, BlockPos pos) {
            this.source = source;
            this.pos = pos;
        }

        AnalysisPackets.State state() {
            return new AnalysisPackets.State(source, session, lease.generation,
                    lease.owner == null ? AnalysisPackets.NONE : lease.owner, pos, track, next, height, position(), active,
                    delay.from(), delay.to(), delay.start());
        }

        long position() {
            return clockAt == 0 ? millis : clockMillis + (System.nanoTime() - clockAt) / 1_000_000;
        }

        boolean hasLiveToken(long tick) {
            liveTokens = (int) Math.min(20, liveTokens + Math.max(0, tick - liveTokenAt));
            liveTokenAt = tick;
            return liveTokens > 0;
        }

        void restart() {
            session = UUID.randomUUID();
            lease = new AnalysisLease();
            ready.clear();
            tracks.entrySet().removeIf(entry -> !entry.getValue().prepared());
            tracks.values().forEach(AnalysisTimeline::clearLive);
            audience.values().forEach(value -> {
                value.sent.clear();
                value.pending = null;
            });
            sentAt = -100;
            clockAt = 0;
            delay = AnalysisDelay.INITIAL;
        }
    }

    public static void restart(BlockEntity source) {
        if (source.getLevel() instanceof ServerLevel level) {
            Map<UUID, Session> sessions = LEVELS.get(level);
            if (sessions != null) {
                Session session = sessions.get(SourceLinks.identify(level, source.getBlockPos()));
                if (session != null) {
                    session.restart();
                }
            }
        }
    }

    public static void use(LightingConsoleBlock.Entity console) {
        if (!(console.getLevel() instanceof ServerLevel level)) {
            return;
        }
        use(level, console.audioSource, console.getBlockPos());
        use(level, console.videoSource, console.getBlockPos());
        AnalysisFrame audio = sample(level, console.audioSource);
        AnalysisFrame video = sample(level, console.videoSource);
        console.hasAudio = audio != null && audio.audio();
        console.hasVideo = video != null && video.video();
        if (console.hasAudio || console.hasVideo) {
            console.signalAt = level.getGameTime();
        }
        console.audioLevel = audio == null ? 0 : audio.level();
        console.bass = audio == null ? 0 : audio.bass();
        console.mid = audio == null ? 0 : audio.mid();
        console.high = audio == null ? 0 : audio.high();
        console.pulse = audio == null ? 0 : audio.pulse();
        console.bpm = audio == null ? 120 : audio.bpm();
        if (video != null) {
            console.videoLeft = video.left();
            console.videoRight = video.right();
        }
    }

    private static AnalysisFrame sample(ServerLevel level, BlockPos pos) {
        Map<UUID, Session> sessions = LEVELS.get(level);
        Session session = sessions == null || pos == null ? null : sessions.get(SourceLinks.identify(level, pos));
        AnalysisTimeline timeline = session == null || !session.active ? null : session.tracks.get(session.track);
        long position = session == null ? 0 : session.position();
        return timeline == null ? null : timeline.sample(position, Math.round(session.delay.at(position)));
    }

    private static void use(ServerLevel level, BlockPos pos, BlockPos console) {
        if (pos == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof MediaSource media)) {
            return;
        }
        UUID id = SourceLinks.identify(level, pos);
        if (id == null) {
            return;
        }
        Map<UUID, Session> sessions = LEVELS.computeIfAbsent(level, ignored -> new HashMap<>());
        if (!sessions.containsKey(id) && sessions.size() >= 32) {
            return;
        }
        Session session = sessions.computeIfAbsent(id, ignored -> new Session(id, pos.immutable()));
        session.pos = pos.immutable();
        session.consoles.add(console.immutable());
        session.touched = level.getGameTime();
        session.preferred = media.initiator();
        Streams.Source playing = Streams.playing(media);
        // Not while its track loads: its position stands at 0 then, and clients, running it on from each state they
        // are sent, would play the lights the track's first half second over and over until it starts.
        boolean active = playing != null && Streams.loaded((BlockEntity) media);
        if (active && (!playing.url().equals(session.track) || !session.active)) {
            session.restart();
        }
        session.active = active;
        if (active) {
            session.track = playing.url();
            session.next = playing.video() == null ? "" : playing.video().next().orElse("");
            session.height = playing.video() == null ? 0 : playing.video().maxHeight();
            session.millis = playing.ticks() * 50;
            session.tracks.keySet().removeIf(track -> !track.equals(session.track) && !track.equals(session.next));
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
                            player.distanceToSqr(SableCompat.toWorld(level, pos, pos.getCenter()))
                                    <= (LightingSettings.MAX_RANGE + 160.0) * (LightingSettings.MAX_RANGE + 160.0));
                    if (!nearby) {
                        continue;
                    }
                    present.add(player.getUUID());
                    boolean joined = !session.audience.containsKey(player.getUUID());
                    Audience audience = session.audience.computeIfAbsent(player.getUUID(), ignored -> new Audience());
                    if (joined || changed || level.getGameTime() - session.sentAt >= 10) {
                        PacketDistributor.sendToPlayer(player, session.state());
                    }
                    if (session.active && batches.getOrDefault(player.getUUID(), 0) < 2 && replay(player, session, audience)) {
                        batches.merge(player.getUUID(), 1, Integer::sum);
                    }
                }
                session.audience.keySet().retainAll(present);
                if (changed || level.getGameTime() - session.sentAt >= 10) {
                    session.sentAt = level.getGameTime();
                }
                session.consoles.clear();
            }
        }
    }

    private static boolean replay(ServerPlayer player, Session session, Audience audience) {
        if (audience.pending != null) {
            long now = System.nanoTime();
            if (now - audience.pendingAt > 5_000_000_000L) {
                PacketDistributor.sendToPlayer(player, audience.pending);
                audience.pendingAt = now;
                return true;
            }
            return false;
        }
        for (String track : List.of(session.track, session.next)) {
            AnalysisTimeline timeline = session.tracks.get(track);
            if (timeline == null || !timeline.prepared()) {
                continue;
            }
            long cursor = audience.sent.getOrDefault(track, track.equals(session.track) ? Math.max(-1, session.position() - 1000) : -1);
            List<AnalysisFrame> frames = timeline.after(cursor, AnalysisPackets.BATCH_SIZE);
            if (!frames.isEmpty()) {
                audience.pending = new AnalysisPackets.Batch(session.source, session.session, track, true, frames);
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

    private static Session session(ServerPlayer player, UUID source, UUID id) {
        Map<UUID, Session> sessions = LEVELS.get(player.serverLevel());
        Session session = sessions == null ? null : sessions.get(source);
        return session != null && session.session.equals(id) && session.active ? session : null;
    }

    public static void status(ServerPlayer player, AnalysisPackets.Status status) {
        Session session = session(player, status.source(), status.session());
        if (session == null || status.millis() < 0 || status.millis() > 604_800_000
                || status.upstreamMillis() < 0 || status.upstreamMillis() > 2_000) {
            return;
        }
        UUID id = player.getUUID();
        Audience audience = session.audience.get(id);
        boolean eligible = session.eligible.contains(id);
        long now = System.nanoTime() / 1_000_000;
        if (audience == null ? !eligible : now - audience.statusAt < 500) {
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
            AnalysisTimeline timeline = session.tracks.get(session.track);
            if (id.equals(session.lease.owner) && timeline != null
                    && timeline.sample(status.millis(), Math.round(session.delay.at(status.millis()))) != null) {
                session.lease.published = now;
            }
        } else if (eligible) {
            session.ready.remove(id);
        }
        if (audience != null && status.clientSentNanos() != 0) {
            PacketDistributor.sendToPlayer(player, new AnalysisPackets.Clock(session.source, session.session,
                    status.clientSentNanos(), session.clockAt == 0 ? -1 : session.position()));
        }
    }

    public static void upload(ServerPlayer player, AnalysisPackets.Upload upload) {
        Session session = session(player, upload.source(), upload.session());
        long tick = player.level().getGameTime();
        if (upload.prepared() && session != null && player.getUUID().equals(session.lease.owner)
                && upload.generation() == session.lease.generation && upload.sequence() == session.lease.sequence) {
            PacketDistributor.sendToPlayer(player, new AnalysisPackets.Accepted(session.source, session.session,
                    session.lease.generation, upload.sequence()));
            return;
        }
        if (session == null || !session.eligible.contains(player.getUUID())
                || !session.lease.accepts(player.getUUID(), upload.generation(), upload.sequence(), upload.prepared())
                || upload.prepared() && tick - session.receivedAt < 1 || upload.frames().isEmpty()
                || !upload.track().equals(session.track) && !upload.track().equals(session.next)
                || !upload.prepared() && (!upload.track().equals(session.track) || upload.frames().size() != 1
                        || !session.hasLiveToken(tick))) {
            return;
        }
        AnalysisTimeline timeline = session.tracks.computeIfAbsent(upload.track(), ignored -> new AnalysisTimeline());
        if (!(upload.prepared() ? timeline.publishPrepared(upload.frames()) : timeline.add(upload.frames(), false))) {
            return;
        }
        if (upload.prepared()) {
            session.receivedAt = tick;
            session.lease.sequence = upload.sequence();
            PacketDistributor.sendToPlayer(player, new AnalysisPackets.Accepted(session.source, session.session,
                    session.lease.generation, upload.sequence()));
        } else {
            session.liveTokens--;
            session.lease.liveSequence = upload.sequence();
        }
        session.lease.published = System.nanoTime() / 1_000_000;
        if (!upload.prepared()) {
            for (ServerPlayer listener : player.serverLevel().players()) {
                if (session.audience.containsKey(listener.getUUID())) {
                    PacketDistributor.sendToPlayer(listener, new AnalysisPackets.Batch(session.source, session.session,
                            session.track, false, upload.frames()));
                }
            }
        }
    }
}
