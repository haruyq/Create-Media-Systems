package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.haruyq.createmediasystems.client.WorldEvents;
import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of the lighting analysis (see AnalysisCoordinator): follows each source's session, and while this
 * player is its owner, sends the signal of the media playing here, ahead from a kept copy where it can. Each client
 * tick, every console here takes its sources' signal from it (see sample).
 */
public final class AnalysisPlayback {
    private static final Map<UUID, Playback> PLAYING = new HashMap<>();
    /** A client tick (ms): how far on a console's motion phase is read, for how fast it runs till the next. */
    private static final long PHASE_STEP_MILLIS = 50;

    private AnalysisPlayback() {
    }

    private static final class Playback extends AnalysisSession {
        AnalysisPackets.State state;
        final Map<String, Integer> uploaded = new HashMap<>();
        final Map<String, List<AnalysisFrame>> prepared = new LinkedHashMap<>();
        final AnalysisClock sharedClock = new AnalysisClock();
        volatile CompletableFuture<List<AnalysisFrame>> job;
        String jobTrack;
        long received;
        long sequence;
        long liveSequence;
        long liveSentAt;
        long lagAt;
        int lagMillis = -1;
        long sentAt;
        long statusAt;
        long retryAt;
        long localAt;
        long localMillis;
        double clockMillis;
        long clockReadAt;
        long progressAt;
        AnalysisFrame previous;
        AnalysisPackets.Upload pending;

        Playback(AnalysisPackets.State state) {
            this.state = state;
        }

        void cancel() {
            CompletableFuture<?> pending = job;
            if (pending != null) {
                pending.cancel(false);
                job = null;
            }
        }

        /** Another owner (generation): what this client sent goes, and it starts sending over. */
        void newOwner() {
            cancel();
            uploaded.clear();
            sequence = 0;
            liveSequence = 0;
            pending = null;
            previous = null;
        }

        @Override
        void restart() {
            super.restart();
            newOwner();
            localAt = 0;
            sharedClock.reset();
            lagMillis = -1;
        }

        boolean owner() {
            var player = Minecraft.getInstance().player;
            return state.active() && player != null && player.getUUID().equals(state.owner());
        }

        long position() {
            return Math.max(0, Math.round(localAt == 0 ? state.millis() + (System.nanoTime() - received) / 1_000_000.0
                    : clockMillis + (System.nanoTime() - localAt) / 1_000_000.0));
        }

        long samplePosition() {
            return sharedClock.position(System.nanoTime(), position());
        }

        boolean updateClock(long now) {
            LightingMedia.Clock clock = LightingMedia.clock(state.pos(), state.track(), now);
            long millis = clock.millis();
            if (millis < 0) {
                return clock.nativePlayer() && localAt != 0 && now - clockReadAt < 2_000_000_000L;
            }
            clockReadAt = now;
            if (localAt == 0 || millis != localMillis) {
                double expected = localAt == 0 ? millis : clockMillis + (now - localAt) / 1_000_000.0;
                clockMillis = Math.abs(expected - millis) > 500 ? millis : expected + (millis - expected) * 0.1;
                localMillis = millis;
                localAt = now;
                progressAt = now;
            }
            return now - progressAt < 5_000_000_000L || clock.liveAndFlowing();
        }
    }

    /** What this client keeps of a source's session, for the development probes. */
    public record Snapshot(AnalysisPackets.State state, boolean owner, long position, long samplePosition, long delay,
                           @Nullable AnalysisTimeline timeline, @Nullable AnalysisTimeline next, long received,
                           @Nullable Long pendingSequence, long sentAt) {
    }

    public static List<Snapshot> snapshots() {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Playback playback : PLAYING.values()) {
            long position = playback.samplePosition();
            snapshots.add(new Snapshot(playback.state, playback.owner(), playback.position(), position,
                    playback.delay(position), playback.timeline(), playback.tracks.get(playback.next), playback.received,
                    playback.pending == null ? null : playback.pending.sequence(), playback.sentAt));
        }
        return snapshots;
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        WorldEvents.onLevelChanged(AnalysisPlayback::clear);
    }

    private static void clear() {
        PLAYING.values().forEach(Playback::cancel);
        PLAYING.clear();
    }

    public static void state(AnalysisPackets.State state) {
        if (Minecraft.getInstance().level == null || state.millis() < 0 || state.millis() > AnalysisFrame.MAX_MILLIS) {
            return;
        }
        Playback playback = PLAYING.get(state.source());
        if (playback == null) {
            if (PLAYING.size() >= AnalysisSession.MAX_SESSIONS) {
                return;
            }
            playback = new Playback(state);
            PLAYING.put(state.source(), playback);
        } else if (!playback.state.session().equals(state.session())) {
            playback.restart();
        } else if (playback.state.generation() != state.generation()) {
            playback.newOwner();
        }
        playback.state = state;
        playback.delay = state.delay();
        playback.received = System.nanoTime();
        playback.follow(state.track(), state.next());
        playback.prepared.keySet().removeIf(track -> !state.track().equals(track) && !state.next().equals(track));
        if (!playback.owner()) {
            playback.cancel();
        }
    }

    public static void batch(AnalysisPackets.Batch batch) {
        Playback playback = PLAYING.get(batch.source());
        if (playback == null || !playback.state.session().equals(batch.session()) || !playback.has(batch.track())) {
            return;
        }
        boolean accepted = playback.timeline(batch.track()).add(batch.frames(), batch.prepared());
        if (accepted && !batch.prepared() && !batch.frames().isEmpty()) {
            long lag = playback.samplePosition() - batch.frames().getLast().millis();
            playback.lagMillis = lag >= 0 && lag <= AnalysisDelay.MAX_MILLIS ? (int) lag : -1;
            playback.lagAt = System.nanoTime();
        }
        if (accepted && batch.prepared() && !batch.frames().isEmpty()) {
            PacketDistributor.sendToServer(new AnalysisPackets.Received(batch.source(), batch.session(), batch.track(),
                    batch.frames().getLast().millis()));
        }
    }

    public static void accepted(AnalysisPackets.Accepted accepted) {
        Playback playback = PLAYING.get(accepted.source());
        if (playback == null || playback.pending == null || !playback.state.session().equals(accepted.session())
                || playback.state.generation() != accepted.generation() || playback.pending.sequence() != accepted.sequence()) {
            return;
        }
        if (playback.pending.prepared()) {
            playback.uploaded.merge(playback.pending.track(), playback.pending.frames().size(), Integer::sum);
        }
        playback.pending = null;
    }

    public static void clock(AnalysisPackets.Clock clock) {
        Playback playback = PLAYING.get(clock.source());
        if (playback != null && playback.state.session().equals(clock.session())
                && clock.echoClientSent() <= playback.statusAt) {
            playback.sharedClock.receive(System.nanoTime(), clock.echoClientSent(), clock.serverMediaMillis());
        }
    }

    private static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().player == null) {
            return;
        }
        long now = System.nanoTime();
        PLAYING.values().removeIf(playback -> {
            if (now - playback.received > 5_000_000_000L) {
                playback.cancel();
                return true;
            }
            return false;
        });
        for (Playback playback : PLAYING.values()) {
            AnalysisPackets.State state = playback.state;
            if (!state.active()) {
                continue;
            }
            boolean ready = playback.updateClock(now);
            long position = playback.position();
            AnalysisTimeline timeline = playback.timeline();
            if (now - playback.statusAt >= AnalysisSession.STATUS_NANOS) {
                PacketDistributor.sendToServer(new AnalysisPackets.Status(state.source(), state.session(), ready, position,
                        now - playback.lagAt < 2_000_000_000L ? playback.lagMillis : -1, now, playback.sharedClock.upstreamMillis()));
                playback.statusAt = now;
            }
            if (!playback.owner() || !ready) {
                continue;
            }
            prepare(playback, now);
            if (playback.pending != null) {
                if (now - playback.sentAt >= 500_000_000L) {
                    PacketDistributor.sendToServer(playback.pending);
                    playback.sentAt = now;
                }
            } else if (now - playback.sentAt >= 100_000_000L && level.getGameTime() % 4 < 2 && uploadPrepared(playback)) {
                playback.sentAt = now;
            }
            if (now - playback.liveSentAt < 100_000_000L) {
                continue;
            }
            playback.liveSentAt = now;
            if (timeline == null || timeline.samplePrepared(position) == null) {
                AnalysisFrame frame = LightingMedia.sample(state.pos(), state.track(), position);
                if (frame != null) {
                    List<AnalysisFrame> prepared = playback.prepared.get(state.track());
                    double phase = prepared == null ? Double.NaN : AnalysisTimeline.phaseAt(prepared, position);
                    if (!Double.isFinite(phase)) {
                        phase = phaseAt(playback, position);
                    }
                    if (Double.isFinite(phase)) {
                        frame = frame.phase(phase);
                    }
                    playback.previous = frame;
                    PacketDistributor.sendToServer(new AnalysisPackets.Upload(state.source(), state.session(), state.generation(),
                            ++playback.liveSequence, state.track(), false, List.of(frame)));
                }
            }
        }
    }

    private static void prepare(Playback playback, long now) {
        if (playback.job != null && playback.job.isDone()) {
            try {
                List<AnalysisFrame> frames = playback.job.join();
                if (!frames.isEmpty()) {
                    if (playback.jobTrack.equals(playback.track)) {
                        long position = playback.position();
                        frames = AnalysisTimeline.align(frames, position, phaseAt(playback, position));
                    }
                    playback.prepared.put(playback.jobTrack, frames);
                    LogUtils.getLogger().info("Prepared lighting analysis: {} frames", frames.size());
                }
            } catch (RuntimeException failed) {
                playback.retryAt = now + 60_000_000_000L;
                LogUtils.getLogger().debug("Cached lighting analysis unavailable", failed);
            }
            playback.job = null;
        }
        if (playback.job != null || now < playback.retryAt) {
            return;
        }
        playback.retryAt = now + 1_000_000_000L;
        for (String track : List.of(playback.next, playback.track)) {
            if (track.isEmpty() || playback.prepared.containsKey(track)) {
                continue;
            }
            try {
                var job = CachedLightingAnalysis.analyze(track, playback.state.height());
                if (job == null) {
                    continue;
                }
                playback.jobTrack = track;
                playback.job = job;
                return;
            } catch (RuntimeException failed) {
                playback.retryAt = now + 60_000_000_000L;
            }
        }
    }

    private static double phaseAt(Playback playback, long position) {
        return AnalysisTimeline.continuePhase(playback.timeline(), playback.previous, position);
    }

    private static boolean uploadPrepared(Playback playback) {
        for (String track : playback.both()) {
            List<AnalysisFrame> frames = playback.prepared.get(track);
            int from = playback.uploaded.getOrDefault(track, 0);
            if (frames == null || from >= frames.size()) {
                continue;
            }
            int to = Math.min(frames.size(), from + AnalysisPackets.BATCH_SIZE);
            send(playback, track, true, frames.subList(from, to));
            return true;
        }
        return false;
    }

    private static void send(Playback playback, String track, boolean prepared, List<AnalysisFrame> frames) {
        AnalysisPackets.State state = playback.state;
        playback.pending = new AnalysisPackets.Upload(state.source(), state.session(), state.generation(),
                ++playback.sequence, track, prepared, List.copyOf(frames));
        PacketDistributor.sendToServer(playback.pending);
    }

    @Nullable
    private static Playback find(@Nullable BlockPos pos) {
        if (pos != null) {
            for (Playback playback : PLAYING.values()) {
                if (playback.state.active() && playback.state.pos().equals(pos)) {
                    return playback;
                }
            }
        }
        return null;
    }

    /**
     * Gives {@code console} the signal its audio and video sources play here now, and where its media is (see
     * LightingConsoleBlock.Playhead): once a client tick, for its lights to draw from.
     */
    static void sample(LightingConsoleBlock.Entity console) {
        Playback audio = find(console.audioSource());
        Playback video = find(console.videoSource());
        long now = System.nanoTime();
        long audioAt = audio == null ? 0 : audio.samplePosition();
        long videoAt = video == null ? 0 : video.samplePosition();
        console.receive(audio == null ? null : audio.sample(audioAt), video == null ? null : video.sample(videoAt),
                console.getLevel().getGameTime());
        double phase = audio == null ? Double.NaN : audio.phaseAt(audioAt);
        // How fast the phase runs till the next tick: as the timeline has it a tick on.
        double ahead = Double.isFinite(phase) ? audio.phaseAt(audioAt + PHASE_STEP_MILLIS) : Double.NaN;
        double speed = Double.isFinite(ahead) ? Math.clamp((ahead - phase) * 1000 / PHASE_STEP_MILLIS, 0, 2) : 1;
        if (!Double.isFinite(phase) && audio != null) {
            phase = audioAt / 1000.0;
        }
        double seconds = audio != null ? audioAt / 1000.0 : video != null ? videoAt / 1000.0 : Double.NaN;
        console.playhead = new LightingConsoleBlock.Playhead(seconds, phase, speed, now);
    }
}
