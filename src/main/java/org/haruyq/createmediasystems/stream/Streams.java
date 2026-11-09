package org.haruyq.createmediasystems.stream;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.disc.DiscUrl;

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
 * Server side of block-placed streams. A <em>source</em> ({@link MediaSource}) owns a track and its position in it;
 * an <em>emitter</em> (a jukebox, which is its own source, or a speaker or display linked to one) sends that track to
 * players as they come into its range, with the current offset so late joiners and every speaker stay in sync.
 */
public final class Streams {
    private static final double UNKNOWN = -1;

    /** Per emitter, what it relays and to whom. */
    private static final Map<BlockEntity, Relay> RELAYS = new WeakHashMap<>();
    /** How often (ticks) emitters relay their source's track, and sources check whether it has ended. */
    public static final int RELAY_TICKS = 10;
    /** Longest a track waits for its listeners to load it: what a client waits for the API (see StreamSound). */
    public static final long MAX_LOAD_TICKS = 90 * 20;
    /**
     * Longest a track waits for the rest of its listeners once one has it loaded: those slower to download it don't
     * hold everyone up for long, and catch up when they have it (see started).
     */
    public static final long BARRIER_TICKS = 10 * 20;
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
     * A source's track as its listeners load it. Its position counts from when they all have it loaded (or the first
     * of them, {@link #BARRIER_TICKS} on), when they are all told to play it together; so time spent loading (a fresh
     * disc first waits out YouTube's pre-roll ad, a slow connection takes its time) is not taken as played: nobody
     * starts that far in, and the track doesn't end that early.
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
        /** The tick count since the track started at which it began to play; LOADING while it loads. */
        long playingFrom = LOADING;
        /** The players it was sent to while it loaded, whom it waits for. */
        final Set<ServerPlayer> expected = new HashSet<>();
        /**
         * Those that have it loaded, waiting to play it, and every emitter each said so of: each is told through all
         * of them, as a client may have opened its sides apart (one native player each), and each waits to be told.
         */
        final Map<ServerPlayer, Set<BlockPos>> ready = new HashMap<>();
        /** The tick count at which the first of them had it loaded; LOADING until then. */
        long firstReadyAt = LOADING;

        Load(@Nullable UUID startedBy, long barrierTicks) {
            this.startedBy = startedBy;
            this.barrierTicks = barrierTicks;
        }
    }

    /** One emitter's stream: who has it, and what was sent, to notice the source or the emitter's sound changing. */
    private static final class Relay {
        final BlockPos pos;
        final Sound sound;
        final Set<ServerPlayer> listeners = new HashSet<>();
        String url;
        long ticks;
        @Nullable
        JukeboxSettings settings;

        Relay(BlockPos pos, Sound sound) {
            this.pos = pos;
            this.sound = sound;
        }
    }

    // ponytail: never evicted, one double per distinct URL played since start; add an LRU if that grows.
    private static final Map<String, Double> DURATIONS = new ConcurrentHashMap<>();
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
     * What a source is playing: the track and how far in it is, and the Blu-ray player it is a video of, if any, whose
     * displays and speakers clients play on one player (see NativeAudio).
     */
    public record Source(String url, long ticks, @Nullable Video video) {
    }

    /**
     * The Blu-ray player a video plays from, the tallest picture (pixels) its disc plays, and the video after it on
     * the disc, which clients get ready ahead.
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
        Source source = level.isLoaded(pos) && level.getBlockEntity(pos) instanceof MediaSource media
                ? playing(media) : null;
        if (source == null || !ServerConfig.isAllowed(source.url())) {
            return null;
        }
        // Clients fetch the next video ahead: it has to pass the same allow-list as the one playing.
        Video video = source.video();
        return video == null || video.next().map(ServerConfig::isAllowed).orElse(true) ? source
                : new Source(source.url(), source.ticks(), new Video(video.player(), video.maxHeight(), Optional.empty()));
    }

    /** What {@code media} plays now, counted from when its listeners had it loaded (see played); null for nothing. */
    @Nullable
    public static Source playing(MediaSource media) {
        Source source = media.nowPlaying();
        return source == null ? null
                : new Source(source.url(), played((BlockEntity) media, source.ticks()), source.video());
    }

    /**
     * {@code source} starts its track from the top: its position waits until its listeners have it (see Load).
     * {@code by} is the player who started it; null when it follows on from the one before (who started that stays).
     */
    public static void loading(BlockEntity source, @Nullable Player by) {
        source.setChanged();
        Load previous = LOADS.get(key(source));
        Source playing = source instanceof MediaSource media ? media.nowPlaying() : null;
        LOADS.put(key(source), new Load(by != null ? by.getUUID() : previous == null ? null : previous.startedBy,
                barrierTicks(playing == null ? "" : playing.url())));
    }

    public static long barrierTicks(String url) {
        return DiscUrl.parse(url) == null ? BARRIER_TICKS : MAX_LOAD_TICKS;
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

    /** Whether an emitter that {@code kind} matches relays the source at {@code source} now. */
    public static boolean relayedBy(Level level, BlockPos source, Class<? extends BlockEntity> kind) {
        return RELAYS.entrySet().stream().anyMatch(entry -> entry.getKey().getLevel() == level
                && kind.isInstance(entry.getKey()) && source.equals(entry.getValue().sound.source()));
    }

    /** Whether emitters relay at {@code level}'s tick now: all on the same ticks (see relay's game event). */
    public static boolean relayDue(Level level) {
        return level.getGameTime() % RELAY_TICKS == 0;
    }

    public static Set<ServerPlayer> listeners(ServerLevel level, BlockPos source) {
        Set<ServerPlayer> players = new HashSet<>();
        RELAYS.forEach((emitter, relay) -> {
            if (emitter.getLevel() == level && source.equals(relay.sound.source())) {
                relay.listeners.stream().filter(player -> !player.isRemoved() && player.level() == level)
                        .forEach(players::add);
            }
        });
        return players;
    }

    /**
     * How many of the {@code ticks} since {@code source} started its track count as played (see Load). Once its
     * listeners have it loaded, or have had long enough, it starts playing, and those waiting are told to play it.
     */
    public static long played(BlockEntity source, long ticks) {
        Load load = LOADS.get(key(source));
        if (load == null) {
            return ticks;
        }
        if (load.playingFrom == LOADING) {
            if (!load.ready.isEmpty() && load.firstReadyAt == LOADING) {
                load.firstReadyAt = ticks;
            }
            load.expected.removeIf(ServerPlayer::isRemoved);
            boolean everyone = !load.ready.isEmpty() && load.ready.keySet().containsAll(load.expected);
            if (everyone || ticks >= MAX_LOAD_TICKS
                    || load.firstReadyAt != LOADING && ticks - load.firstReadyAt >= load.barrierTicks) {
                load.playingFrom = ticks;
                load.ready.forEach((player, emitters) -> {
                    if (!player.isRemoved()) {
                        emitters.forEach(emitter ->
                                PacketDistributor.sendToPlayer(player, new StreamPackets.SyncAtBlock(emitter, 0)));
                    }
                });
                load.ready.clear();
                load.expected.clear();
            }
        }
        return load.playingFrom == LOADING ? 0 : ticks - load.playingFrom;
    }

    /**
     * Whether {@code source}'s track plays, its listeners having it loaded (or having had long enough); false while
     * it loads, when its position stands at 0 (see played). True for one never waited on.
     */
    public static boolean loaded(BlockEntity source) {
        Load load = LOADS.get(key(source));
        return load == null || load.playingFrom != LOADING;
    }

    /** A source block entity is known by its id, which it keeps when Sable moves it or its chunk reloads. */
    private static UUID key(BlockEntity source) {
        return source.getData(CreateMediaSystems.SOURCE_ID);
    }

    @Nullable
    private static Load loadOf(Level level, BlockPos source) {
        return level.isLoaded(source) && level.getBlockEntity(source) instanceof MediaSource media
                ? LOADS.get(key((BlockEntity) media)) : null;
    }

    /**
     * {@code player} has the stream of the emitter at {@code pos} loaded, paused until told to play it. Only from a
     * player that emitter sent it to. While its source's track loads, that is once every listener has it (see
     * played); once it plays, at once, from how far in it is now: a player that took longer to download it, or joined
     * later, catches up there (see StreamPackets.SyncAtBlock). {@code duration} is the track's length as that
     * player's VLC sees it (0 if unknown, or live): it stands in for the API's, for tracks the API can't tell the
     * length of (pages only yt-dlp knows), so a Blu-ray player moves on when they end.
     */
    public static void started(ServerPlayer player, BlockPos pos, float duration) {
        Level level = player.level();
        BlockEntity emitter = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        Relay relay = emitter == null ? null : RELAYS.get(emitter);
        if (relay == null) {
            return;
        }
        BlockPos sourcePos = relay.sound.source();
        if (!relay.listeners.contains(player)) {
            // That emitter lost this player (out of its range) while another of the source's still has them: the
            // length they tell is still the only one there may be for a track the API can't tell.
            if (listeners(player.serverLevel(), sourcePos).contains(player)) {
                learnDuration(source(level, sourcePos), duration);
            }
            return;
        }
        Load load = loadOf(level, sourcePos);
        boolean loading = load != null && load.playingFrom == LOADING;
        if (loading) {
            load.ready.computeIfAbsent(player, p -> new HashSet<>()).add(pos.immutable());
        }
        // Counts it as played so far, which plays it now if this player was the last it waited for.
        Source playing = source(level, sourcePos);
        learnDuration(playing, duration);
        if (playing != null && !loading) {
            PacketDistributor.sendToPlayer(player, new StreamPackets.SyncAtBlock(pos, playing.ticks() / 20f));
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

    /** Whether {@code source} has played past its track's length, once the API has told us that length. */
    public static boolean endedAt(Source source) {
        return endedAt(source.url(), source.ticks());
    }

    /** Whether {@code url} has played past its length {@code ticks} in; false while that length is unknown. */
    public static boolean endedAt(String url, long ticks) {
        double duration = duration(url);
        return duration > 0 && ticks >= duration * 20;
    }

    /**
     * The emitter plays its source's track (the source at {@code sound.source()}, if it plays one), sounding as
     * {@code sound}, at {@code settings}; emitters call this every RELAY_TICKS, and at once when something changed. Players newly in range
     * get the stream, from where it is now; those who left are told to stop (they get it again on return). A different track, the source starting over, or a different sound starts everyone over; new
     * settings reach current listeners without restarting their audio.
     */
    public static void relay(ServerLevel level, BlockEntity emitter, Sound sound, JukeboxSettings settings) {
        BlockPos pos = emitter.getBlockPos();
        Source playing = source(level, sound.source());
        Relay relay = RELAYS.get(emitter);
        if (relay != null && (playing == null || !playing.url().equals(relay.url) || playing.ticks() < relay.ticks
                || !sound.equals(relay.sound) || !pos.equals(relay.pos))) {
            stop(emitter);
            relay = null;
        }
        if (playing == null) {
            return;
        }
        if (relay == null) {
            relay = new Relay(pos.immutable(), sound);
            RELAYS.put(emitter, relay);
        }
        relay.url = playing.url();
        relay.ticks = playing.ticks();
        // Wardens and sculk sensors hear it once a second (jukebox_play is no vibration), like a goat horn: a warden
        // goes to see what it is. Every other relay tick, as emitters all relay on the same ticks.
        if (settings.volume() > 0 && level.getGameTime() % (2 * RELAY_TICKS) == 0) {
            level.gameEvent(GameEvent.INSTRUMENT_PLAY, SableCompat.toWorld(level, pos, sound.origin()),
                    GameEvent.Context.of(level.getBlockState(pos)));
        }
        boolean retune = !settings.equals(relay.settings);
        relay.settings = settings;
        // A relogged or respawned player is a new object, so they get a fresh stream too. One who left is told: their
        // client plays a side while any of its emitters is near, and an emitter never stopped, once they were back
        // within its drop margin, kept it playing after the disc came out (silent that far, its video on screen).
        relay.listeners.removeIf(p -> {
            boolean keep = !p.isRemoved() && p.level() == level
                    && hears(level, pos, sound, settings, p, JukeboxSettings.DROP_MARGIN);
            if ((retune || !keep) && !p.isRemoved()) {
                PacketDistributor.sendToPlayer(p, keep ? new StreamPackets.RetuneAtBlock(pos, settings)
                        : new StreamPackets.StopAtBlock(pos));
            }
            return !keep;
        });
        Load load = loadOf(level, sound.source());
        for (ServerPlayer player : level.players()) {
            if (hears(level, pos, sound, settings, player, 0) && relay.listeners.add(player)) {
                if (load != null && load.playingFrom == LOADING) {
                    load.expected.add(player);
                }
                PacketDistributor.sendToPlayer(player, new StreamPackets.PlayAtBlock(playing.url(), pos,
                        playing.ticks() / 20f, settings, sound, Optional.ofNullable(playing.video()),
                        load == null ? 0 : load.id));
            }
        }
    }

    /**
     * {@link #relay} for an emitter linked to the source at {@code link}, at {@code volume} times the source's own (see
     * MediaSource#volume). An unlinked one points at its own place, where no source plays.
     */
    public static void relay(ServerLevel level, BlockEntity emitter, Optional<BlockPos> link, Vec3 origin,
                             Optional<Direction> facing, Channel channel, float volume) {
        BlockPos source = link.orElse(emitter.getBlockPos());
        float sourceVolume = level.isLoaded(source) && level.getBlockEntity(source) instanceof MediaSource media
                ? media.volume() : 1;
        relay(level, emitter, new Sound(origin, facing, channel, source),
                new JukeboxSettings(Optional.empty(), volume * sourceVolume));
    }

    private static boolean hears(ServerLevel level, BlockPos pos, Sound sound, JukeboxSettings settings,
                                 ServerPlayer player, double margin) {
        return settings.inRange(pos, sound.origin(), SableCompat.toBlockSpace(level, pos, player.position()), margin);
    }

    /** Stops {@code emitter}'s stream for everyone still listening to it. */
    public static void stop(BlockEntity emitter) {
        Relay relay = RELAYS.remove(emitter);
        if (relay != null) {
            relay.listeners.stream().filter(p -> !p.isRemoved())
                    .forEach(p -> PacketDistributor.sendToPlayer(p, new StreamPackets.StopAtBlock(relay.pos)));
        }
    }

    /** Track length in seconds from the API; negative while loading or for live/unknown streams. */
    public static double duration(String url) {
        Double cached = DURATIONS.putIfAbsent(url, UNKNOWN);
        if (cached != null) {
            return cached;
        }
        info(url).exceptionally(e -> {
            LogUtils.getLogger().warn("Audio API /info failed for {}: {}; asking again in {}s", url, e.getMessage(),
                    INFO_RETRY_SECONDS);
            // Else a Blu-ray player, disc reader or jukebox waits on it forever, unless a listener tells its length.
            CompletableFuture.delayedExecutor(INFO_RETRY_SECONDS, TimeUnit.SECONDS)
                    .execute(() -> DURATIONS.remove(url, UNKNOWN));
            return null;
        });
        return UNKNOWN;
    }

    /**
     * Asks the API about {@code url}. Fails with the API's reason (its {@code detail}) when the URL can't be
     * played, so a disc can be refused before it is written. Also fills the duration cache.
     */
    public static CompletableFuture<TrackInfo> info(String url) {
        if (DiscUrl.parse(url) != null) {
            return CompletableFuture.completedFuture(new TrackInfo(null, "DVD / Blu-ray"));
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.failedFuture(new IOException("Not a link"));
        }
        if (UrlPolicy.isRtsp(uri)) {
            DURATIONS.put(url, UNKNOWN);
            return CompletableFuture.completedFuture(new TrackInfo(null, "RTSP live"));
        }
        return HTTP.sendAsync(HttpRequest.newBuilder(URI.create(UrlPolicy.endpoint(ServerConfig.API_URL.get(), "info", ProviderUrls.canonical(uri).toString())))
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new CompletionException(new IOException(UrlPolicy.errorDetail(response.statusCode(), response.body())));
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

    @Nullable
    private static <T> T optional(JsonObject object, String key, Function<JsonElement, T> read) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : read.apply(value);
    }
}
