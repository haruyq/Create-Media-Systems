package org.haruyq.createmediasystems.stream;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.ServerConfig;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of streams. A <em>source</em> ({@link MediaSource}) owns a track and its position in it; an
 * <em>emitter</em> (a jukebox, which is its own source, or an {@link Emitter} linked to one) sends that track to
 * players as they come into its {@link EmitterRange}, with the current offset so late joiners and every speaker stay
 * in sync. A source that is no block (see MediaSource.Keyed) has emitters that pick their own audience instead.
 */
public final class Streams {
    private static final double UNKNOWN = -1;

    private static final Map<Object, Relay> RELAYS = new WeakHashMap<>();
    /** How often (ticks of game time) emitters relay their source's track, and clocks ask whether it has ended. */
    public static final int RELAY_TICKS = 10;
    /** Longest a track waits for its listeners to load it: what a client waits for the API (see StreamSound). */
    public static final long MAX_LOAD_TICKS = 90 * PlaybackClock.TICKS_PER_SECOND;
    /**
     * Longest a track waits for the rest of its listeners once one has it loaded: those slower to download it don't
     * hold everyone up for long, and catch up when they have it (see started).
     */
    public static final long BARRIER_TICKS = 10 * PlaybackClock.TICKS_PER_SECOND;
    private static final long LOADING = -1;
    /**
     * Per source, how its listeners load its track (see Load). Kept by the source's identity, or its place before it
     * is linked (see key), so moving or reloading a block entity keeps its playback offset.
     */
    // ponytail: never evicted, one entry per place a track was started since the server started; prune on removal if that grows.
    private static final Map<UUID, Load> LOADS = new HashMap<>();
    /**
     * The last Load#id given out; on the server thread. From a random start, so a client still holding a playback
     * from before a server restart never takes a new load for it.
     */
    private static long lastLoadId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);

    /**
     * A source's track as its listeners load it. Its clock stands until they all have it loaded (or the first of them,
     * {@link #BARRIER_TICKS} on), when they are all told to play it together; so time spent loading (a fresh disc first
     * waits out YouTube's pre-roll ad, a slow connection takes its time) is not taken as played: nobody starts that far
     * in, and the track doesn't end that early.
     */
    private static final class Load {
        /**
         * Which load of a track this is, sent with every stream of it: clients take streams of one load for one
         * playback, however far apart they arrive (see PlayAtBlock#load). Never 0, which means unknown.
         */
        final long id = ++lastLoadId;
        /** The player who started it (or the track before it, which it follows on from); null for none. */
        @Nullable
        final UUID startedBy;
        final long barrierTicks;
        /** How far into the track it starts (ticks): 0 for the top, more for one taken up where it was left. */
        final long offset;
        final boolean unattended;
        /** The game time it began to load. */
        final long since;
        /** The game time it began to play; LOADING while it loads. */
        long playingFrom = LOADING;
        /** The players it was sent to while it loaded, whom it waits for. */
        final Set<ServerPlayer> expected = new HashSet<>();
        /**
         * Those that have it loaded, waiting to play it, and every emitter each said so of: each is told through all
         * of them, as a client may have opened its sides apart (one native player each), and each waits to be told.
         */
        final Map<ServerPlayer, Set<StreamPackets.Target>> ready = new HashMap<>();
        /** The game time the first of them had it loaded; LOADING until then. */
        long firstReadyAt = LOADING;

        Load(@Nullable UUID startedBy, long barrierTicks, long offset, boolean unattended, long since) {
            this.startedBy = startedBy;
            this.barrierTicks = barrierTicks;
            this.offset = offset;
            this.unattended = unattended;
            this.since = since;
        }

        boolean open(long now) {
            if (playingFrom != LOADING) {
                return true;
            }
            expected.removeIf(ServerPlayer::isRemoved);
            long age = now - since;
            boolean everyone = !ready.isEmpty() && ready.keySet().containsAll(expected);
            boolean nobody = unattended && age >= 2 * RELAY_TICKS && expected.isEmpty() && ready.isEmpty();
            if (everyone || nobody || age >= MAX_LOAD_TICKS
                    || firstReadyAt != LOADING && now - firstReadyAt >= barrierTicks) {
                playingFrom = now;
                ready.forEach((player, emitters) -> {
                    if (!player.isRemoved()) {
                        emitters.forEach(emitter -> send(player, emitter.sync(offset / (float) PlaybackClock.TICKS_PER_SECOND)));
                    }
                });
                ready.clear();
                expected.clear();
            }
            return playingFrom != LOADING;
        }
    }

    private static final class Relay {
        /** How its listeners' clients name it. */
        final StreamPackets.Target target;
        /** A block emitter's sound, which says where its source is; null for a keyed source's emitter. */
        @Nullable
        final Sound sound;
        /** The source it plays when that is no block; null for a block emitter's. */
        @Nullable
        final MediaSource.Keyed keyed;
        final Set<ServerPlayer> listeners = new HashSet<>();
        String url;
        long ticks;
        /** Which load of its source's track it relays (see Load#id): a new one starts everyone over. */
        long load;
        @Nullable
        EmitterRange range;

        Relay(StreamPackets.Target target, @Nullable Sound sound, @Nullable MediaSource.Keyed keyed) {
            this.target = target;
            this.sound = sound;
            this.keyed = keyed;
        }

        boolean sameAs(Relay other) {
            return target.equals(other.target) && Objects.equals(sound, other.sound) && keyed == other.keyed;
        }

        boolean sameSource(Relay other) {
            return keyed != null ? keyed == other.keyed : other.sound != null && sound.source().equals(other.sound.source());
        }

        @Nullable
        Source playing(Level level) {
            return keyed != null ? allowed(keyed.nowPlaying()) : source(level, sound.source());
        }

        /** How its source's listeners load its track; null for one never waited on. */
        @Nullable
        Load load(Level level) {
            return keyed != null ? LOADS.get(keyed.id()) : loadOf(level, sound.source());
        }
    }

    // ponytail: never evicted, one double per distinct URL played since start; add an LRU if that grows.
    private static final Map<String, Double> DURATIONS = new ConcurrentHashMap<>();
    /** Tracks the API answered /info for with an error (see duration): they end at once, as nobody can play them. */
    private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();
    /** How long a track whose /info failed waits before it is asked about again (see duration). */
    static final long INFO_RETRY_SECONDS = 60;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** What the API's /info says about a track. */
    public record TrackInfo(@Nullable Double duration, @Nullable String title) {
    }

    /**
     * What a source is playing: the track and how far in it is, and the source it is a video of, if any, whose
     * displays and speakers clients play on one player (see NativeAudio).
     */
    public record Source(String url, long ticks, @Nullable Video video) {
    }

    /**
     * The source (a VideoSource) a video plays from, the tallest picture (pixels) its disc plays (see DiscKind), and
     * the video after it on the disc, which clients get ready ahead.
     */
    public record Video(BlockPos player, int maxHeight, Optional<String> next) {
        public static final StreamCodec<ByteBuf, Video> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Video::player,
                ByteBufCodecs.VAR_INT, Video::maxHeight,
                ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), Video::next,
                Video::new);
    }

    /** Which side of the track an emitter plays: put a LEFT and a RIGHT speaker on one source for stereo. */
    public enum Channel {
        MIX(null), LEFT("left"), RIGHT("right");

        public static final StreamCodec<ByteBuf, Channel> CODEC = ByteBufCodecs.idMapper(Channel::byId, Channel::ordinal);

        /** The API's {@code channel} value; null for the whole mix. */
        @Nullable
        public final String api;

        Channel(@Nullable String api) {
            this.api = api;
        }

        public Component label() {
            return Component.translatable("create_media_systems.speaker.channel." + name().toLowerCase(Locale.ROOT));
        }

        public static Channel byId(int id) {
            return values()[Mth.clamp(id, 0, values().length - 1)];
        }
    }

    /**
     * How an emitter sounds: from {@code origin} (in its block space, see SableCompat), loudest towards
     * {@code facing} if it has one, playing {@code channel} of the track of the source at {@code source}. Clients
     * play every emitter of one source as one playback.
     */
    public record Sound(Vec3 origin, Optional<Direction> facing, Channel channel, BlockPos source) {
        /** Share of the volume heard straight behind a directional emitter; sides get about half. A tuning knob. */
        static final double BEHIND_GAIN = 0.15;

        private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
        public static final StreamCodec<ByteBuf, Sound> CODEC = StreamCodec.composite(
                VEC3, Sound::origin,
                ByteBufCodecs.optional(Direction.STREAM_CODEC), Sound::facing,
                Channel.CODEC, Sound::channel,
                BlockPos.STREAM_CODEC, Sound::source,
                Sound::new);

        /** Volume multiplier for a listener at {@code listener}: 1 straight ahead, {@link #BEHIND_GAIN} straight behind. */
        public double directivity(Vec3 listener) {
            Vec3 toListener = listener.subtract(origin);
            if (facing.isEmpty() || toListener.lengthSqr() < 1e-6) {
                return 1;
            }
            double cos = toListener.normalize().dot(Vec3.atLowerCornerOf(facing.get().getNormal()));
            return BEHIND_GAIN + (1 - BEHIND_GAIN) * Mth.clamp((1 + cos) / 2, 0, 1);
        }
    }

    private Streams() {
    }

    /** What the source at {@code pos} is playing now, or null (nothing, unloaded, disallowed). */
    @Nullable
    public static Source source(Level level, BlockPos pos) {
        return allowed(level.isLoaded(pos) && level.getBlockEntity(pos) instanceof MediaSource media ? media.nowPlaying() : null);
    }

    @Nullable
    private static Source allowed(@Nullable Source source) {
        if (source == null || !UrlPolicy.isAllowed(source.url())) {
            return null;
        }
        // Clients fetch the next video ahead: it has to pass the same allow-list as the one playing.
        Video video = source.video();
        return video == null || video.next().map(UrlPolicy::isAllowed).orElse(true) ? source
                : new Source(source.url(), source.ticks(), new Video(video.player(), video.maxHeight(), Optional.empty()));
    }

    /**
     * {@code source}'s listeners load what {@code clock} plays, from where it is at game time {@code now}: it stands
     * there until they all have it (see tick). A source calls this as it starts a track, or takes one up where it was
     * left (a tape played on, a disc sought). {@code by} is the player who started it; null when it follows on from the
     * one before (who started that stays).
     */
    public static void loading(BlockEntity source, @Nullable Player by, PlaybackClock clock, long now) {
        source.setChanged();
        Source playing = source instanceof MediaSource media ? media.nowPlaying() : null;
        loading(key(source), by, MediaKind.barrierTicks(playing == null ? null : playing.url()), clock, now);
    }

    /** {@link #loading} for a source that is no block: it plays from when the first listener has it. */
    public static void loading(MediaSource.Keyed source, @Nullable Player by, PlaybackClock clock, long now) {
        loading(source.id(), by, 0, clock, now);
    }

    private static void loading(UUID key, @Nullable Player by, long barrierTicks, PlaybackClock clock, long now) {
        clock.hold(now);
        Load previous = LOADS.get(key);
        LOADS.put(key, new Load(by != null ? by.getUUID() : previous == null ? null : previous.startedBy,
                barrierTicks, clock.position(now), clock.playsUnattended(), now));
    }

    /**
     * One tick at game time {@code now} of {@code source}, whose listeners load what {@code clock} plays (see loading):
     * the clock stands while they load it, and counts on once they all have it, or have had long enough.
     */
    public static void tick(BlockEntity source, PlaybackClock clock, long now) {
        tick(key(source), clock, now);
    }

    public static void tick(MediaSource.Keyed source, PlaybackClock clock, long now) {
        tick(source.id(), clock, now);
    }

    private static void tick(UUID key, PlaybackClock clock, long now) {
        Load load = clock.running() ? LOADS.get(key) : null;
        clock.tick(now, load == null || load.open(now));
    }

    /** Which load of its track {@code source} plays (see Load#id); 0 for one never loaded since the server started. */
    public static long loadId(BlockEntity source) {
        Load load = LOADS.get(key(source));
        return load == null ? 0 : load.id;
    }

    @Nullable
    public static UUID startedBy(BlockEntity source) {
        Load load = LOADS.get(key(source));
        return load == null ? null : load.startedBy;
    }

    /** Whether emitters relay at {@code level}'s tick now: all on the same ticks (see relay's game event). */
    public static boolean relayDue(Level level) {
        return level.getGameTime() % RELAY_TICKS == 0;
    }

    /**
     * The source at {@code source} changed how it sounds (its volume, its displays' lighting): every emitter relaying it
     * relays now, so its listeners hear the change at once rather than at the next relay.
     */
    public static void changed(ServerLevel level, BlockPos source) {
        RELAYS.entrySet().stream().filter(entry -> relays(entry, level, source)).map(Map.Entry::getKey).toList()
                .forEach(emitter -> {
                    if (emitter instanceof Emitter linked && !((BlockEntity) emitter).isRemoved()) {
                        linked.relay(level);
                    }
                });
    }

    private static boolean relays(Map.Entry<Object, Relay> entry, Level level, BlockPos source) {
        Sound sound = entry.getValue().sound;
        return entry.getKey() instanceof BlockEntity emitter && emitter.getLevel() == level && sound != null
                && source.equals(sound.source());
    }

    public record RelayView(Sound sound, int listeners) {
    }

    public static List<RelayView> relays() {
        return RELAYS.values().stream().map(relay -> new RelayView(relay.sound, relay.listeners.size())).toList();
    }

    public static Set<ServerPlayer> listeners(ServerLevel level, BlockPos source) {
        Set<ServerPlayer> players = new HashSet<>();
        RELAYS.entrySet().stream().filter(entry -> relays(entry, level, source))
                .forEach(entry -> players.addAll(listening(level, entry.getValue())));
        return players;
    }

    private static List<ServerPlayer> listening(Level level, Relay relay) {
        return relay.listeners.stream().filter(player -> !player.isRemoved() && player.level() == level).toList();
    }

    /**
     * Whether {@code source}'s track plays, its listeners having it loaded (or having had long enough); false while
     * it loads, when its clock stands (see tick). True for one never waited on.
     */
    public static boolean loaded(BlockEntity source) {
        Load load = LOADS.get(key(source));
        return load == null || load.playingFrom != LOADING;
    }

    /** A source block entity is known by its id, which it keeps when Sable moves it or its chunk reloads. */
    private static UUID key(BlockEntity source) {
        return source.getData(StreamFeature.SOURCE_ID);
    }

    @Nullable
    private static Load loadOf(Level level, BlockPos source) {
        return level.isLoaded(source) && level.getBlockEntity(source) instanceof MediaSource media
                ? LOADS.get(key((BlockEntity) media)) : null;
    }

    /**
     * {@code player} has the stream of the emitter at {@code pos} loaded, paused until told to play it. Only from a
     * player that emitter sent it to. While its source's track loads, that is once every listener has it (see
     * Load#open); once it plays, at once, from how far in it is now: a player that took longer to download it, or joined
     * later, catches up there (see StreamPackets.SyncAtBlock). {@code duration} is the track's length as that
     * player's VLC sees it (0 if unknown, or live): it stands in for the API's, for tracks the API can't tell the
     * length of (pages only yt-dlp knows), so a Blu-ray player moves on when they end.
     */
    public static void started(ServerPlayer player, BlockPos pos, float duration) {
        started(player, new StreamPackets.Target.Block(pos.immutable()), duration);
    }

    public static void started(ServerPlayer player, StreamPackets.Target target, float duration) {
        Level level = player.level();
        Relay relay = relayOf(level, target);
        if (relay == null) {
            return;
        }
        if (!relay.listeners.contains(player)) {
            // That emitter lost this player (out of its range) while another of the source's still has them: the
            // length they tell is still the only one there may be for a track the API can't tell.
            if (RELAYS.values().stream().anyMatch(other -> other.sameSource(relay) && listening(level, other).contains(player))) {
                learnDuration(relay.playing(level), duration);
            }
            return;
        }
        Load load = relay.load(level);
        boolean loading = load != null && load.playingFrom == LOADING;
        if (loading) {
            load.ready.computeIfAbsent(player, p -> new HashSet<>()).add(target);
            if (load.firstReadyAt == LOADING) {
                load.firstReadyAt = level.getGameTime();
            }
            load.open(level.getGameTime());
        }
        Source playing = relay.playing(level);
        learnDuration(playing, duration);
        if (playing != null && !loading) {
            send(player, target.sync(playing.ticks() / (float) PlaybackClock.TICKS_PER_SECOND));
        }
    }

    @Nullable
    private static Relay relayOf(Level level, StreamPackets.Target target) {
        if (target instanceof StreamPackets.Target.Block block) {
            BlockEntity emitter = level.isLoaded(block.pos()) ? level.getBlockEntity(block.pos()) : null;
            return emitter == null ? null : RELAYS.get(emitter);
        }
        return RELAYS.values().stream().filter(relay -> relay.target.equals(target)).findFirst().orElse(null);
    }

    private static void send(ServerPlayer player, @Nullable CustomPacketPayload payload) {
        if (payload != null) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * A listener's VLC says {@code playing}'s track is {@code duration} seconds long (0 if it can't tell): that stands
     * unless the API has told a length already. Also while its /info waits to be asked again (see duration).
     */
    static void learnDuration(@Nullable Source playing, double duration) {
        if (playing != null && duration > 0) {
            DURATIONS.compute(playing.url(), (url, known) -> known != null && known > 0 ? known : duration);
        }
    }

    /**
     * Whether {@code url} has played past its length {@code ticks} in; false while that length is unknown. True for a
     * track the API refused: no listener can load it either, so a deck or player moves on past it.
     */
    public static boolean endedAt(String url, long ticks) {
        double duration = duration(url);
        return REFUSED.contains(url) || duration > 0 && ticks >= duration * PlaybackClock.TICKS_PER_SECOND;
    }

    /**
     * The emitter plays its source's track (the source at {@code sound.source()}, if it plays one), sounding as
     * {@code sound}, heard over {@code range}; emitters call this every RELAY_TICKS, and at once when something
     * changed. Players newly in range get the stream, from where it is now; those who left are told to stop (they get
     * it again on return). A different track, the source starting over, or a different sound starts everyone over; a
     * new range reaches current listeners without restarting their audio.
     */
    public static void relay(ServerLevel level, BlockEntity emitter, Sound sound, EmitterRange range) {
        BlockPos pos = emitter.getBlockPos();
        Source playing = update(level, emitter, new Relay(new StreamPackets.Target.Block(pos.immutable()), sound, null));
        if (playing == null) {
            return;
        }
        Relay relay = RELAYS.get(emitter);
        Load load = relay.load(level);
        // Wardens and sculk sensors hear it once a second (jukebox_play is no vibration), like a goat horn: a warden
        // goes to see what it is. Every other relay tick, as emitters all relay on the same ticks.
        if (range.volume() > 0 && level.getGameTime() % (2 * RELAY_TICKS) == 0) {
            level.gameEvent(GameEvent.INSTRUMENT_PLAY, SableCompat.toWorld(level, pos, sound.origin()),
                    GameEvent.Context.of(level.getBlockState(pos)));
        }
        boolean retune = !range.equals(relay.range);
        relay.range = range;
        // A relogged or respawned player is a new object, so they get a fresh stream too. One who left is told: their
        // client plays a side while any of its emitters is near, and an emitter never stopped, once they were back
        // within its drop margin, kept it playing after the disc came out (silent that far, its video on screen).
        relay.listeners.removeIf(p -> {
            boolean keep = !p.isRemoved() && p.level() == level
                    && range.hears(level, pos, sound.origin(), p.position(), EmitterRange.DROP_MARGIN);
            if ((retune || !keep) && !p.isRemoved()) {
                PacketDistributor.sendToPlayer(p, keep ? new StreamPackets.RetuneAtBlock(pos, range)
                        : new StreamPackets.StopAtBlock(pos));
            }
            return !keep;
        });
        for (ServerPlayer player : level.players()) {
            if (range.hears(level, pos, sound.origin(), player.position(), 0) && relay.listeners.add(player)) {
                join(load, player, new StreamPackets.PlayAtBlock(playing.url(), pos,
                        playing.ticks() / (float) PlaybackClock.TICKS_PER_SECOND, range, sound, Optional.ofNullable(playing.video()), relay.load));
            }
        }
    }

    /**
     * {@link #relay} for {@code emitter} of a source that is no block, whose listeners its clients know it by
     * {@code target}, to {@code audience} (those aboard a train): each that joins it gets {@code play} of what it plays,
     * with its load (see Load#id); those who left are dropped, and stop it themselves (see StreamPackets.Target).
     */
    public static void relay(ServerLevel level, Object emitter, StreamPackets.Target target, MediaSource.Keyed source,
                             Collection<ServerPlayer> audience, BiFunction<Source, Long, CustomPacketPayload> play) {
        Source playing = update(level, emitter, new Relay(target, null, source));
        if (playing == null) {
            return;
        }
        Relay relay = RELAYS.get(emitter);
        relay.listeners.removeIf(player -> player.isRemoved() || !audience.contains(player));
        Load load = relay.load(level);
        for (ServerPlayer player : audience) {
            if (relay.listeners.add(player)) {
                join(load, player, play.apply(playing, relay.load));
            }
        }
    }

    /**
     * {@code emitter} relays as {@code fresh} says, its source playing what it returns (null: nothing, and it stops). A
     * different track, the source starting over or a new load of it (a tape played on further in), or a different
     * sound or place, starts everyone over.
     */
    @Nullable
    private static Source update(ServerLevel level, Object emitter, Relay fresh) {
        Source playing = fresh.playing(level);
        Load load = fresh.load(level);
        long loadId = load == null ? 0 : load.id;
        Relay relay = RELAYS.get(emitter);
        if (relay != null && (playing == null || !playing.url().equals(relay.url) || playing.ticks() < relay.ticks
                || relay.load != loadId || !relay.sameAs(fresh))) {
            stop(emitter);
            relay = null;
        }
        if (playing == null) {
            return null;
        }
        if (relay == null) {
            relay = fresh;
            RELAYS.put(emitter, relay);
        }
        relay.url = playing.url();
        relay.ticks = playing.ticks();
        relay.load = loadId;
        return playing;
    }

    private static void join(@Nullable Load load, ServerPlayer player, CustomPacketPayload play) {
        if (load != null && load.playingFrom == LOADING) {
            load.expected.add(player);
        }
        PacketDistributor.sendToPlayer(player, play);
    }

    /**
     * {@link #relay} for {@code emitter} linked to the source at {@code link}, over the default range at {@code volume}
     * times the source's own (see MediaSource#gain); silent while it gives way to another (see Emitter#givesWay). An
     * unlinked one points at its own place, where no source plays.
     */
    public static <E extends BlockEntity & Emitter> void relay(ServerLevel level, E emitter, Optional<BlockPos> link,
                                                               Vec3 origin, Optional<Direction> facing, Channel channel,
                                                               float volume) {
        BlockPos source = link.orElse(emitter.getBlockPos());
        float sourceGain = level.isLoaded(source) && level.getBlockEntity(source) instanceof MediaSource media
                ? media.gain() : 1;
        boolean givenWay = emitter.givesWay() && RELAYS.entrySet().stream().anyMatch(entry -> entry.getKey() != emitter
                && relays(entry, level, source) && !(entry.getKey() instanceof Emitter other && other.givesWay()));
        relay(level, emitter, new Sound(origin, facing, channel, source),
                EmitterRange.at(givenWay ? 0 : volume * sourceGain));
    }

    public static void stop(Object emitter) {
        Relay relay = RELAYS.remove(emitter);
        if (relay != null) {
            relay.listeners.stream().filter(p -> !p.isRemoved()).forEach(p -> send(p, relay.target.stop()));
        }
    }

    /**
     * Where a seek to {@code to} ticks into {@code url} lands: not before its top, and a second short of its end once
     * its length is known, so it plays a moment instead of ending on the spot.
     */
    public static long seekable(String url, long to) {
        double duration = duration(url);
        int second = PlaybackClock.TICKS_PER_SECOND;
        return Math.max(0, duration > 0 ? Math.min(to, (long) (duration * second) - second) : to);
    }

    /** Track length in seconds from the API; negative while loading or for live/unknown streams. */
    public static double duration(String url) {
        Double cached = DURATIONS.putIfAbsent(url, UNKNOWN);
        if (cached != null) {
            return cached;
        }
        info(url).exceptionally(e -> {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            boolean refused = cause instanceof Refused;
            if (refused) {
                REFUSED.add(url);
            }
            LogUtils.getLogger().warn("Audio API /info failed for {}: {}; {}asking again in {}s", url, cause.getMessage(),
                    refused ? "skipping it, " : "", INFO_RETRY_SECONDS);
            // Else a Blu-ray player, disc reader or jukebox waits on it forever, unless a listener tells its length.
            CompletableFuture.delayedExecutor(INFO_RETRY_SECONDS, TimeUnit.SECONDS).execute(() -> {
                REFUSED.remove(url);
                DURATIONS.remove(url, UNKNOWN);
            });
            return null;
        });
        return UNKNOWN;
    }

    /**
     * Asks the API about {@code url}. Fails with the API's reason (its {@code detail}) when the URL can't be
     * played, so a disc can be refused before it is written. Also fills the duration cache.
     */
    public static CompletableFuture<TrackInfo> info(String url) {
        MediaKind kind = MediaKind.of(url);
        if (kind == null) {
            return CompletableFuture.failedFuture(new IOException("Not a link"));
        }
        if (kind.title != null) {
            return CompletableFuture.completedFuture(new TrackInfo(null, kind.title));
        }
        URI uri = URI.create(url.trim());
        return HTTP.sendAsync(HttpRequest.newBuilder(URI.create(ApiHttp.endpoint(ServerConfig.API_URL.get(), "info", ProviderUrls.canonical(uri).toString())))
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new CompletionException(new Refused(ApiHttp.errorDetail(response.statusCode(), response.body())));
                    }
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    TrackInfo info = new TrackInfo(optional(body, "duration", JsonElement::getAsDouble),
                            optional(body, "title", JsonElement::getAsString));
                    if (info.duration() != null) {
                        DURATIONS.put(url, info.duration());
                    }
                    return info;
                });
    }

    /** The API answered, with an error: the host isn't allowed, or the track can't be played (see endedAt). */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    @Nullable
    private static <T> T optional(JsonObject object, String key, Function<JsonElement, T> read) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : read.apply(value);
    }
}
