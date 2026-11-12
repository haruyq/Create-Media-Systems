package org.haruyq.createmediasystems.playback;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.broadcast.BroadcastOcclusion;
import org.haruyq.createmediasystems.broadcast.BroadcastProfile;
import org.haruyq.createmediasystems.broadcast.client.BroadcastAudioStream;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.Reports;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.StreamFeature;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTSourceRadius;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The streams playing on this computer, one per {@link Slot}: one side of a source's track (see Side), one train disc
 * reader's announcements, one music emitter's; different slots play at the same time.
 */
public final class ClientStreams {
    private static final Set<Slot> PLAYING = new LinkedHashSet<>();
    /** Live range per emitter block; RetuneAtBlock updates it without restarting the audio. */
    private static final Map<BlockPos, EmitterRange> RANGES = new HashMap<>();
    private static final Map<Side, Group> GROUPS = new HashMap<>();
    private static final Map<BlockPos, Side> SIDES = new HashMap<>();

    /**
     * One side (see Streams.Channel) of the track of the source at {@code source}. Every emitter playing it here is
     * heard through one stream, so a source takes at most 3 sound channels (its mix, left and right) however many
     * jukeboxes, speakers and displays play it: one each was an OpenAL source each, and a wall of speakers froze the
     * game waiting on the sound thread.
     */
    private record Side(BlockPos source, Streams.Channel channel, BroadcastProfile profile) {
    }

    /**
     * Where one stream plays here at a time: a stream played on it replaces the one there. It plays while that stream
     * plays, loads or waits for a channel, and starts it again where it is if the sound engine drops it.
     */
    public static final class Slot {
        private final String name;
        /** The side it plays, for a source's; null for one of its own (an announcement, a music emitter). */
        @Nullable
        private final Side side;
        @Nullable
        private StreamSound sound;

        /** A slot of its own, known in the log as {@code name}. */
        public Slot(String name) {
            this(name, null);
        }

        private Slot(String name, @Nullable Side side) {
            this.name = name;
            this.side = side;
        }

        public void play(StreamSound next) {
            var sounds = Minecraft.getInstance().getSoundManager();
            StreamSound previous = sound;
            sound = next;
            PLAYING.add(this);
            if (previous != null) {
                sounds.stop(previous);
                previous.release();
            }
            sounds.play(next);
        }

        /** Whether a stream plays here, including while it loads or waits for a channel. */
        public boolean playing() {
            return sound != null;
        }

        public void stop() {
            StreamSound current = sound;
            sound = null;
            PLAYING.remove(this);
            if (current != null) {
                Minecraft.getInstance().getSoundManager().stop(current);
                current.release();
            }
        }

        /** Where in its track what is heard now is, of an API stream (s); -1 if nothing is, or it plays natively. */
        public double audibleSeconds() {
            return sound == null ? -1 : sound.audibleSeconds();
        }

        /** Where in its track the API was last asked to stream from (s); -1 before it was. */
        public double requestedSeconds() {
            return sound == null ? -1 : sound.requestedSeconds();
        }

        public void sync(double seconds) {
            if (sound != null) sound.sync(seconds);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The emitters playing one side, and how they sound together as of this tick (see update). */
    private static final class Group {
        /** The playback they play, as the first of them asked for it: emitters of the same one join it. */
        final PlaybackId playback;
        /** Where it plays: the side's, kept as a new playback replaces this one. */
        final Slot slot;
        final BroadcastProfile profile;
        final Map<BlockPos, Streams.Sound> emitters = new LinkedHashMap<>();
        boolean present;
        boolean nearby;
        double gain;
        @Nullable
        Vec3 at;
        /**
         * Where Sound Physics traces it from: its loudest emitter. Traced from {@link #at}, between them, the ray
         * passed through the emitters themselves and what they stand on, and it was muffled by their own blocks.
         */
        @Nullable
        Vec3 tracedFrom;
        /** How wide it is heard (see Mix#radius). */
        double radius;
        /** The stream last given a radius (a restarted one is a new OpenAL source), and that radius. */
        @Nullable
        private StreamSound spreadOn;
        private double spreadRadius;
        private volatile BroadcastAudioStream.Path path = new BroadcastAudioStream.Path(0, false);
        private long lastTrace = Long.MIN_VALUE;

        Group(PlaybackId playback, Slot slot, BroadcastProfile profile) {
            this.playback = playback;
            this.slot = slot;
            this.profile = profile;
        }

        /** Sums up its emitters as heard from the camera now (see Mix); its stream asks this every tick. */
        void update() {
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            Mix mix = new Mix(camera);
            present = false;
            nearby = false;
            Vec3 first = null;
            for (var emitter : emitters.entrySet()) {
                BlockPos pos = emitter.getKey();
                Streams.Sound sound = emitter.getValue();
                Vec3 world = toWorld(pos, sound.origin());
                first = first == null ? world : first;
                if (!sound.profile().outdoor() && !blockPresent(pos)) {
                    continue;
                }
                present = true;
                nearby |= inRange(pos, sound.origin());
                Vec3 ear = toBlockSpace(pos, camera);
                EmitterRange range = range(pos);
                double volume = profile.outdoor() ? range.volume() : range.gain(pos, sound.origin(), ear);
                mix.add(world, volume * sound.directivity(ear));
            }
            gain = mix.gain();
            Vec3 heard = mix.at();
            at = heard != null ? heard : first != null ? first : at;
            tracedFrom = mix.loudest() != null ? mix.loudest() : at;
            if (profile.outdoor() && tracedFrom != null) updatePath(camera, tracedFrom);
            radius = heard != null ? mix.radius() : 0;
        }

        private void updatePath(Vec3 camera, Vec3 from) {
            double distance = camera.distanceTo(from);
            boolean blocked = path.obstructed();
            var mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null && (lastTrace == Long.MIN_VALUE
                    || mc.level.getGameTime() - lastTrace >= 5)) {
                lastTrace = mc.level.getGameTime();
                blocked = BroadcastOcclusion.obstructed(mc.player, camera, from);
            }
            path = new BroadcastAudioStream.Path(distance, blocked);
            gain *= path.transmission();
        }

        /**
         * Gives {@code sound}, its stream, the width its emitters are heard over (see Mix#radius), once its channel
         * is there and again as that width changes; each change waits its turn on the sound thread, so not for less.
         */
        void spread(@Nullable StreamSound sound) {
            if (sound == null || sound == spreadOn && Math.abs(radius - spreadRadius) <= 0.05 * Math.max(1, spreadRadius)) {
                return;
            }
            float given = (float) radius;
            boolean playing = SoundChannels.withSource(sound, source -> {
                // Without it (not OpenAL Soft), a point between them, as heard from the listener.
                if (AL.getCapabilities().AL_EXT_SOURCE_RADIUS) {
                    AL10.alSourcef(source, EXTSourceRadius.AL_SOURCE_RADIUS, given);
                }
            });
            if (playing) {
                spreadOn = sound;
                spreadRadius = given;
            }
        }
    }

    /**
     * Emitters of one side, heard from {@code listener} as one stream: as loud as their sound powers summed (they
     * play the same samples, from different places), from their middle, weighted by that power, and as wide as they
     * spread around the listener. One speaker is heard where it is, a wall in front over its
     * width, and standing among a side's speakers, from all around. A stereo pair's sides stay two streams.
     */
    static final class Mix {
        private final Vec3 listener;
        private double power;
        /** Power-weighted sums of the directions from the listener (unit vectors). */
        private double dx;
        private double dy;
        private double dz;
        /** Power-weighted sums of where they are. */
        private double cx;
        private double cy;
        private double cz;
        @Nullable
        private Vec3 loudest;
        private double loudestPower;

        Mix(Vec3 listener) {
            this.listener = listener;
        }

        void add(Vec3 at, double gain) {
            double p = gain * gain;
            Vec3 to = at.subtract(listener);
            double length = to.length();
            power += p;
            cx += p * at.x;
            cy += p * at.y;
            cz += p * at.z;
            if (p > loudestPower) {
                loudest = at;
                loudestPower = p;
            }
            // One at the listener's head is all around it: no direction, so it widens the rest.
            if (length > 1e-3) {
                dx += p * to.x / length;
                dy += p * to.y / length;
                dz += p * to.z / length;
            }
        }

        /** At most 1, as the engine plays it no louder. */
        double gain() {
            return Math.min(1, Math.sqrt(power));
        }

        /**
         * How much they are heard from one direction: 1 all from one, 0 evenly all around (the length of their
         * mean direction).
         */
        double focus() {
            return power > 0 ? Math.min(1, Math.sqrt(dx * dx + dy * dy + dz * dz) / power) : 0;
        }

        /**
         * Where to play it from: their middle, which is among them, so never behind them. Their mean direction, as
         * far as they are on average, was: before a wall, its speakers are further on average than the wall is, and
         * it played from up to 3 blocks behind it, where Sound Physics heard it through the wall. Null while none of
         * them is heard; at the listener when they are evenly all around.
         */
        @Nullable
        Vec3 at() {
            return power > 0 ? new Vec3(cx / power, cy / power, cz / power) : null;
        }

        @Nullable
        Vec3 loudest() {
            return loudest;
        }

        /**
         * The angle they are heard over (radians): none from one direction, a half circle at focus 0.5, all around
         * at 0. ponytail: a plain curve through those three, not measured against how speakers are laid out.
         */
        double spread() {
            return 2 * Math.acos(Mth.clamp(2 * focus() - 1, -1, 1));
        }

        /**
         * The OpenAL source radius that plays {@link #at()} over {@link #spread()}. OpenAL Soft spreads a source of
         * radius r at distance d over 2·asin(r/d), up to a half circle at r = d, and inside it over 2π − d/r·π.
         */
        double radius() {
            double spread = spread();
            Vec3 at = at();
            double d = at == null ? 0 : at.distanceTo(listener);
            if (d < 1e-3) {
                return 1; // at the listener: any radius is all around
            }
            return spread <= Math.PI ? d * Math.sin(spread / 2) : d * Math.PI / Math.max(2 * Math.PI - spread, Math.PI / 16);
        }
    }

    private ClientStreams() {
    }

    /**
     * One source's side playing here, for dev probes: its emitters, how they are heard together as of this tick, and
     * its stream (null while none plays), and whether that waits for a channel.
     */
    public record SideView(BlockPos source, Streams.Channel channel, int emitters, boolean present, boolean nearby,
                           double gain, @Nullable SoundInstance sound, boolean waitingForChannel) {
    }

    public static List<SideView> sides() {
        return GROUPS.entrySet().stream().map(entry -> {
            Group group = entry.getValue();
            StreamSound sound = group.slot.sound;
            return new SideView(entry.getKey().source(), entry.getKey().channel(), group.emitters.size(),
                    group.present, group.nearby, group.gain, sound, sound != null && sound.waitingForChannel());
        }).toList();
    }

    /** How many streams play for a side no emitter here plays any more (none, unless one leaked). */
    public static long orphans() {
        return PLAYING.stream().filter(slot -> slot.side != null && !GROUPS.containsKey(slot.side)).count();
    }

    public static void register(IEventBus modBus) {
        StreamPackets.client(new StreamPackets.Client() {
            @Override
            public void play(StreamPackets.PlayAtBlock payload) {
                ClientStreams.play(payload);
            }

            @Override
            public void retune(StreamPackets.RetuneAtBlock payload) {
                ClientStreams.retune(payload);
            }

            @Override
            public void sync(StreamPackets.SyncAtBlock payload) {
                ClientStreams.sync(payload);
            }

            @Override
            public void stop(StreamPackets.StopAtBlock payload) {
                ClientStreams.stop(payload);
            }
        });
        modBus.addListener((FMLClientSetupEvent event) -> {
            // Loading Vlc downloads it the first time, so its class is first touched on a thread of its own.
            Thread.ofPlatform().daemon().name("CMS VLC loader").start(Vlc::preload);
            Tools.preload();
            if (ClientConfig.YOUTUBE_COOKIES.get()) {
                CookieReceiver.start();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> ExtensionUrls.refresh());
        NeoForge.EVENT_BUS.addListener(ClientStreams::muteVanillaJukeboxSound);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> GROUPS.forEach((side, group) -> {
            group.update();
            group.spread(group.slot.sound);
        }));
        // Every playback here plays through the tone of what shows or plays it here now (see Playback#equalize).
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                for (NativeAudio.Playback playback : NativeAudio.playbacks()) {
                    BlockPos at = playback.at();
                    if (at != null) {
                        playback.equalize(level.getBlockEntity(at) instanceof MediaSource media ? media.tone() : Tone.FLAT);
                    }
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientStreams::releaseFinished);
        NeoForge.EVENT_BUS.addListener(ClientStreams::resolveNext);
        NeoForge.EVENT_BUS.addListener(ClientStreams::reportDownloads);
        if (SoundPhysicsCompat.available()) {
            LogUtils.getLogger().info("Sound Physics Remastered found: CMS streams get occlusion and reverb");
        }
    }

    /**
     * The emitter at {@code payload.pos()} plays: it joins its side's stream if that plays the same playback, else
     * that side starts over with it, keeping the emitters it had (on a restart the server sends each of them again).
     */
    public static void play(StreamPackets.PlayAtBlock payload) {
        BlockPos pos = payload.pos().immutable();
        Streams.Sound sound = payload.sound();
        RANGES.put(pos, payload.range());
        Side side = new Side(sound.source(), sound.channel(), sound.profile());
        Side was = SIDES.put(pos, side);
        if (was != null && !was.equals(side)) {
            leave(was, pos);
        }
        Group group = GROUPS.get(side);
        PlaybackId playback = PlaybackId.of(payload.url(), side.source(), payload.load(), null, payload.startSeconds(),
                System.nanoTime());
        if (group != null && group.playback.same(playback)) {
            group.emitters.put(pos, sound);
            return;
        }
        Group fresh = new Group(playback, group != null ? group.slot : new Slot(side.toString(), side), side.profile());
        if (group != null) {
            fresh.emitters.putAll(group.emitters);
        }
        fresh.emitters.put(pos, sound);
        fresh.update();
        GROUPS.put(side, fresh);
        LogUtils.getLogger().info("CMS stream {} plays {} from {}s (load {})", side, payload.url(), payload.startSeconds(),
                payload.load());
        payload.video().ifPresent(video -> video.next().ifPresent(next -> MediaSources.fetchAhead(next, video)));
        var heard = new StreamSpec.Emitter(() -> fresh.at, () -> fresh.tracedFrom, () -> fresh.present,
                () -> fresh.nearby, () -> fresh.gain, null, !side.profile().outdoor());
        StreamSpec spec = new StreamSpec(payload.url(), payload.startSeconds(), side.channel(),
                SoundSource.RECORDS, heard,
                // Through any one of its emitters: the server tells the source's listeners, not each emitter's.
                seconds -> Minecraft.getInstance().execute(() -> {
                    LogUtils.getLogger().info("CMS stream {} loaded, {}s long (0: unknown)", side, seconds);
                    fresh.emitters.keySet().stream().findFirst()
                            .ifPresent(at -> PacketDistributor.sendToServer(new StreamPackets.Started(at, (float) seconds)));
                })).from(side.source(), payload.load(), payload.video().orElse(null));
        if (side.profile().outdoor()) {
            spec = spec.decorated(opened -> BroadcastAudioStream.wrap(opened, side.profile(), () -> fresh.path));
        }
        fresh.slot.play(new StreamSound(spec));
    }

    /**
     * What plays {@code track} here stops, as what it played from is gone: it starts over when the server next sends
     * it. A load of it still under way then ends without word to the server, which would take that word for the load
     * that follows (see Streams#started).
     */
    public static void drop(String track) {
        GROUPS.values().stream().filter(group -> group.playback.track().equals(track)).forEach(group -> group.slot.stop());
    }

    private static void leave(Side side, BlockPos pos) {
        Group group = GROUPS.get(side);
        if (group != null && group.emitters.remove(pos) != null && group.emitters.isEmpty()) {
            GROUPS.remove(side);
            group.slot.stop();
        }
    }

    public static void retune(StreamPackets.RetuneAtBlock payload) {
        RANGES.put(payload.pos(), payload.range());
    }

    public static void sync(StreamPackets.SyncAtBlock payload) {
        Side side = SIDES.get(payload.pos());
        Group group = side == null ? null : GROUPS.get(side);
        StreamSound sound = group == null ? null : group.slot.sound;
        var mc = Minecraft.getInstance();
        if (sound != null && mc.player != null && mc.getConnection() != null) {
            // The server's position as it sent this; it is half our ping further on by now.
            PlayerInfo self = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            sound.sync(payload.seconds() + (self == null ? 0 : self.getLatency() / 2000.0));
        }
    }

    public static void stop(StreamPackets.StopAtBlock payload) {
        RANGES.remove(payload.pos());
        Side side = SIDES.remove(payload.pos());
        if (side != null) {
            leave(side, payload.pos());
        }
    }

    /** A playing stream broke off: posted to chat, unless {@code sound} was already replaced or stopped. */
    static void interrupted(StreamSound sound, String reason) {
        Minecraft.getInstance().execute(() -> {
            if (PLAYING.stream().anyMatch(slot -> slot.sound == sound)) {
                Reports.show(Component.translatable("create_media_systems.stream.interrupted", reason), false);
            }
        });
    }

    private static EmitterRange range(BlockPos pos) {
        return RANGES.getOrDefault(pos, EmitterRange.DEFAULT);
    }

    /**
     * In the last minute of each video playing here, resolves the next on its Blu-ray player, unless it is kept
     * already: it then starts without waiting on yt-dlp, which takes its own time on every computer, and the
     * listeners all have it loaded about as soon as each other (see Streams#tick).
     */
    private static void resolveNext(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.getGameTime() % 20 != 0) {
            return;
        }
        for (Slot slot : PLAYING) {
            Streams.Video video = slot.sound.video();
            if (video != null && video.next().isPresent()) {
                NativeAudio.Playback playback = NativeAudio.playback(video.player());
                double left = playback == null ? -1 : playback.remaining();
                if (left >= 0 && left <= MediaSources.RESOLVE_AHEAD_SECONDS) {
                    MediaSources.resolveAhead(video.next().get(), video);
                }
            }
        }
    }

    /**
     * Once a second, tells the server how far each video source's video is downloaded here: all of it once it plays
     * here (streamed, it needs no more) or is kept, else how much of its copy is (see VideoCache). Its goggles show
     * its listeners' average while any of them waits. Every second, not only on a change, as the server forgets what
     * it was told whenever a track starts over.
     */
    private static void reportDownloads(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.getGameTime() % 20 != 0) {
            return;
        }
        Map<BlockPos, String> told = new HashMap<>();
        for (Slot slot : PLAYING) {
            StreamSound sound = slot.sound;
            Streams.Video video = sound.video();
            if (video == null || told.putIfAbsent(video.player(), sound.track()) != null) {
                continue;
            }
            NativeAudio.Playback playback = NativeAudio.playback(video.player());
            NativeAudio.Progress progress = playback == null ? null : playback.progress();
            double downloaded = progress != null && !progress.loading() ? 1
                    : Math.max(0, MediaSources.downloaded(sound.track(), video));
            PacketDistributor.sendToServer(new StreamPackets.DownloadedAtBlock(video.player(), sound.track(),
                    (float) downloaded));
        }
    }

    /** Covers every way a sound ends (stopped, finished, culled, disconnect), including before it started. */
    private static void releaseFinished(ClientTickEvent.Post event) {
        var sounds = Minecraft.getInstance().getSoundManager();
        for (Slot slot : List.copyOf(PLAYING)) {
            StreamSound sound = slot.sound;
            if (sounds.isActive(sound)) {
                continue;
            }
            // As things are now, not as of this tick's start: leaving the world (the server closing, a kick) stops
            // every sound mid-tick, and its emitters, still there then, made it look dropped, so every stream was
            // started again as the player left.
            Group group = slot.side == null ? null : GROUPS.get(slot.side);
            if (group != null) {
                group.update();
            }
            if (sound.wantsRestart()) {
                LogUtils.getLogger().info("The sound engine dropped CMS stream {} as its audio ran dry; starting it again", slot);
                slot.play(sound.again());
            } else if (sound.waitingForChannel() && sound.retryChannelNow()) {
                // Never started, so the same sound: it still joins its playback where it is now (see NativeAudio).
                sounds.play(sound);
            }
        }
        PLAYING.removeIf(slot -> {
            boolean finished = !sounds.isActive(slot.sound) && !slot.sound.waitingForChannel();
            if (finished) {
                slot.sound.release();
                slot.sound = null;
                Group group = slot.side == null ? null : GROUPS.remove(slot.side);
                if (group != null) {
                    group.emitters.keySet().forEach(pos -> {
                        SIDES.remove(pos, slot.side);
                        RANGES.remove(pos);
                    });
                }
            }
            return finished;
        });
    }

    /** The emitter is still there (the server stops its stream once it stops playing). */
    private static boolean blockPresent(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) != null;
    }

    /** Within the range past which the server stops tracking us (it resends on return): the server's own check. */
    private static boolean inRange(BlockPos pos, Vec3 origin) {
        var player = Minecraft.getInstance().player;
        return player != null && range(pos).hears(player.level(), pos, origin, player.position(), EmitterRange.DROP_MARGIN);
    }

    /** Where the block-space position {@code local} is in the world now (see SableCompat). */
    private static Vec3 toWorld(BlockPos anchor, Vec3 local) {
        var level = Minecraft.getInstance().level;
        return level == null ? local : SableCompat.toWorld(level, anchor, local);
    }

    /** {@code global} as seen from {@code pos}, which may be on a moving structure (see SableCompat). */
    private static Vec3 toBlockSpace(BlockPos pos, Vec3 global) {
        var level = Minecraft.getInstance().level;
        return level == null ? global : SableCompat.toBlockSpace(level, pos, global);
    }

    /** The vanilla jukebox also plays the custom song's placeholder event; only our stream should be heard. */
    private static void muteVanillaJukeboxSound(PlaySoundEvent event) {
        if (event.getSound() != null && !(event.getSound() instanceof StreamSound)
                && event.getSound().getLocation().equals(StreamFeature.STREAM.getId())) {
            event.setSound(null);
        }
    }
}
