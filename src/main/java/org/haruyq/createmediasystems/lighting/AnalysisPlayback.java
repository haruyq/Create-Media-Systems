package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.haruyq.createmediasystems.playback.CachedLightingAnalysis;
import org.haruyq.createmediasystems.playback.LightingMedia;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of the lighting analysis (see AnalysisCoordinator): follows each source's session, and while this
 * player is its owner, sends the signal of the media playing here, ahead from a kept copy where it can.
 */
public final class AnalysisPlayback {
    private static final Map<UUID, Playback> PLAYING = new HashMap<>();
    private static ClientLevel level;

    private AnalysisPlayback() {
    }

    private static final class Playback {
        AnalysisPackets.State state;
        final Map<String, AnalysisTimeline> tracks = new HashMap<>();
        final Map<String, Integer> uploaded = new HashMap<>();
        final Map<String, List<AnalysisFrame>> prepared = new LinkedHashMap<>();
        final AnalysisClock sharedClock = new AnalysisClock();
        volatile CompletableFuture<List<LightingMedia.Sample>> job;
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

        long delay(long position) {
            return Math.round(new AnalysisDelay(state.delayFrom(), state.delayTo(), state.delayStart()).at(position));
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

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    private static void clear() {
        PLAYING.values().forEach(Playback::cancel);
        PLAYING.clear();
        level = null;
    }

    public static void state(AnalysisPackets.State state) {
        checkLevel();
        if (level == null || state.millis() < 0 || state.millis() > AnalysisFrame.MAX_MILLIS) {
            return;
        }
        Playback playback = PLAYING.get(state.source());
        if (playback == null) {
            if (PLAYING.size() >= 32) {
                return;
            }
            playback = new Playback(state);
            PLAYING.put(state.source(), playback);
        } else if (!playback.state.session().equals(state.session())) {
            playback.cancel();
            playback.tracks.entrySet().removeIf(entry -> !entry.getValue().prepared());
            playback.tracks.values().forEach(AnalysisTimeline::clearLive);
            playback.uploaded.clear();
            playback.sequence = 0;
            playback.liveSequence = 0;
            playback.pending = null;
            playback.localAt = 0;
            playback.sharedClock.reset();
            playback.previous = null;
            playback.lagMillis = -1;
        } else if (playback.state.generation() != state.generation()) {
            playback.cancel();
            playback.uploaded.clear();
            playback.sequence = 0;
            playback.liveSequence = 0;
            playback.pending = null;
            playback.previous = null;
        }
        playback.state = state;
        playback.received = System.nanoTime();
        playback.tracks.keySet().removeIf(track -> !track.equals(state.track()) && !track.equals(state.next()));
        playback.prepared.keySet().removeIf(track -> !track.equals(state.track()) && !track.equals(state.next()));
        if (!playback.owner()) {
            playback.cancel();
        }
    }

    public static void batch(AnalysisPackets.Batch batch) {
        Playback playback = PLAYING.get(batch.source());
        if (playback == null || !playback.state.session().equals(batch.session())
                || !batch.track().equals(playback.state.track()) && !batch.track().equals(playback.state.next())) {
            return;
        }
        boolean accepted = playback.tracks.computeIfAbsent(batch.track(), ignored -> new AnalysisTimeline())
                .add(batch.frames(), batch.prepared());
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

    private static void checkLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) {
            clear();
            level = current;
        }
    }

    private static void tick() {
        checkLevel();
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
            AnalysisTimeline timeline = playback.tracks.get(state.track());
            if (now - playback.statusAt >= 1_000_000_000L) {
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
                LightingMedia.Sample sample = LightingMedia.sample(state.pos(), state.track());
                if (sample != null) {
                    AnalysisFrame frame = frame(position, sample);
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
                List<AnalysisFrame> frames = frames(playback.job.join());
                if (!frames.isEmpty()) {
                    if (playback.jobTrack.equals(playback.state.track())) {
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
        for (String track : List.of(playback.state.next(), playback.state.track())) {
            if (track.isEmpty() || playback.prepared.containsKey(track)) {
                continue;
            }
            try {
                var job = CachedLightingAnalysis.analyzeCached(track, playback.state.height(),
                        AnalysisFrame.MAX_DURATION, AnalysisFrame.INTERVAL);
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

    private static AnalysisFrame frame(long millis, LightingMedia.Sample sample) {
        return new AnalysisFrame(millis, sample.level(), sample.bass(), sample.mid(), sample.high(), sample.pulse(),
                Math.clamp(sample.bpm(), AnalysisFrame.MIN_BPM, AnalysisFrame.MAX_BPM), sample.leftColor(),
                sample.rightColor(), sample.audio(), sample.video());
    }

    /** Frames of {@code samples} taken every INTERVAL from the start, their motion phase run on from 0. */
    private static List<AnalysisFrame> frames(List<LightingMedia.Sample> samples) {
        List<AnalysisFrame> frames = new ArrayList<>(samples.size());
        double phase = 0;
        for (LightingMedia.Sample sample : samples) {
            AnalysisFrame frame = frame((long) frames.size() * AnalysisFrame.INTERVAL, sample);
            if (!frames.isEmpty()) {
                phase += AnalysisFrame.INTERVAL / 1000.0 * frames.getLast().motionSpeed();
            }
            frames.add(frame.phase(phase));
        }
        return List.copyOf(frames);
    }

    private static double phaseAt(Playback playback, long position) {
        return AnalysisTimeline.continuePhase(playback.tracks.get(playback.state.track()), playback.previous, position);
    }

    private static boolean uploadPrepared(Playback playback) {
        for (String track : List.of(playback.state.track(), playback.state.next())) {
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

    private static Playback find(BlockPos pos) {
        if (pos != null) {
            for (Playback playback : PLAYING.values()) {
                if (playback.state.active() && playback.state.pos().equals(pos)) {
                    return playback;
                }
            }
        }
        return null;
    }

    private static AnalysisFrame sample(Playback playback) {
        AnalysisTimeline timeline = playback == null ? null : playback.tracks.get(playback.state.track());
        long position = playback == null ? 0 : playback.samplePosition();
        return timeline == null ? null : timeline.sample(position, playback.delay(position));
    }

    public static double apply(LightingConsoleBlock.Entity console, double fallbackSeconds) {
        Playback audio = find(console.audioSource);
        Playback video = find(console.videoSource);
        console.receive(sample(audio), sample(video), console.getLevel().getGameTime());
        AnalysisTimeline timeline = audio == null ? null : audio.tracks.get(audio.state.track());
        long position = audio == null ? 0 : audio.samplePosition();
        double phase = timeline == null ? Double.NaN : timeline.phaseAt(position, audio.delay(position));
        console.motionPhase = Double.isFinite(phase) ? phase : audio == null ? fallbackSeconds : audio.samplePosition() / 1000.0;
        Playback clock = audio == null ? video : audio;
        return clock == null ? fallbackSeconds : clock.samplePosition() / 1000.0;
    }
}
