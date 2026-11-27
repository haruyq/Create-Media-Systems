package org.haruyq.createmediasystems.blocks.train;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;
import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Announcements other mods play on a train (see api.train.TrainAnnouncements): one at a time per train, through its
 * train speakers to those aboard each carriage, as a disc reader's are, from no block. Also what each disc reader on a
 * train plays, and who is told when one starts. On the server thread.
 */
public final class TrainAnnouncer implements MediaSource.Keyed {
    private static final Map<UUID, TrainAnnouncer> PLAYING = new HashMap<>();
    /** Each train's disc readers seen ticking, by the train they are on; gone with their carriage. */
    static final Map<TrainDiscReaderBehaviour.Reader, UUID> READERS = new WeakHashMap<>();

    /** Told when a disc reader on a train starts an announcement from its schedule. */
    public interface Announced {
        void announced(MinecraftServer server, UUID train, String url);
    }

    private static final List<Announced> ANNOUNCED = new CopyOnWriteArrayList<>();

    private final UUID id = UUID.randomUUID();
    private final UUID train;
    private final PlaybackClock clock = new PlaybackClock();
    private String url = "";
    private float volume;

    private TrainAnnouncer(UUID train) {
        this.train = train;
    }

    public static void onAnnounced(Announced announced) {
        ANNOUNCED.add(announced);
    }

    static void announced(MinecraftServer server, UUID train, String url) {
        ANNOUNCED.forEach(a -> a.announced(server, train, url));
    }

    /** Plays {@code url} on {@code train} at {@code volume} (0 to 1), cutting off what this played there before. */
    public static boolean play(MinecraftServer server, UUID train, String url, float volume) {
        if (Create.RAILWAYS.trains.get(train) == null || !UrlPolicy.isAllowed(url)) {
            return false;
        }
        TrainAnnouncer announcer = PLAYING.computeIfAbsent(train, TrainAnnouncer::new);
        announcer.url = url;
        announcer.volume = Float.isNaN(volume) ? 1 : Mth.clamp(volume, 0, 1);
        long now = server.overworld().getGameTime();
        announcer.clock.start(0);
        Streams.loading(announcer, null, announcer.clock, now);
        announcer.relay(server, now);
        return true;
    }

    /** Stops what this plays on {@code train}: whether it played anything. */
    public static boolean stop(UUID train) {
        TrainAnnouncer announcer = PLAYING.remove(train);
        if (announcer != null) {
            Streams.stop(announcer);
        }
        return announcer != null;
    }

    /** What plays on {@code train} now: each of its disc readers' announcements, then this one's. */
    public static List<Streams.Source> playing(UUID train) {
        List<Streams.Source> playing = new ArrayList<>();
        READERS.forEach((reader, on) -> {
            Streams.Source source = on.equals(train) ? reader.nowPlaying() : null;
            if (source != null) {
                playing.add(source);
            }
        });
        TrainAnnouncer announcer = PLAYING.get(train);
        Streams.Source source = announcer == null ? null : announcer.nowPlaying();
        if (source != null) {
            playing.add(source);
        }
        return playing;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PLAYING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (TrainAnnouncer announcer : List.copyOf(PLAYING.values())) {
            Streams.tick(announcer, announcer.clock, now);
            if (Create.RAILWAYS.trains.get(announcer.train) == null || !UrlPolicy.isAllowed(announcer.url)
                    || announcer.clock.ended(announcer.url, now)) {
                stop(announcer.train);
            } else if (now % Streams.RELAY_TICKS == 0) {
                announcer.relay(server, now);
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        PLAYING.clear();
        READERS.clear();
    }

    /** Tells those aboard {@code train} to play it, or to stop: those who got off, or all once its train is gone. */
    private void relay(MinecraftServer server, long now) {
        Train on = Create.RAILWAYS.trains.get(train);
        ServerLevel level = on == null ? null : TrainDiscReaderBehaviour.carriages(on)
                .map(carriage -> carriage.level() instanceof ServerLevel at ? at : null).filter(at -> at != null)
                .findFirst().orElse(null);
        if (level == null) {
            return;
        }
        String channel = "api/" + train;
        List<Integer> carriages = TrainDiscReaderBehaviour.carriageIds(on);
        float volume = this.volume;
        Streams.relay(level, this, new StreamPackets.Target.Channel(channel), this, TrainDiscReaderBehaviour.passengers(on),
                (playing, load) -> new TrainDiscReaderBehaviour.Announce(playing.url(), channel, carriages,
                        playing.ticks() / (float) PlaybackClock.TICKS_PER_SECOND, volume));
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    @Nullable
    public Streams.Source nowPlaying() {
        return clock.running() ? new Streams.Source(url, clock.position(), null) : null;
    }
}
