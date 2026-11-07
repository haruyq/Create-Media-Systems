package org.haruyq.createmediasystems.playback;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleFunction;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.Reports;
import org.haruyq.createmediasystems.mixin.ChannelAccessor;
import org.haruyq.createmediasystems.mixin.SoundEngineAccessor;
import org.haruyq.createmediasystems.mixin.SoundManagerAccessor;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTSourceRadius;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
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
 * The streams playing on this computer, one per channel: one side of a source's track (keyed by its {@link Side}),
 * or one train disc reader's announcements (keyed by a string); different channels play at the same time.
 */
public final class ClientStreams {
    private static final Map<Object, StreamSound> PLAYING = new HashMap<>();
    /** Live settings per emitter block; RetuneAtBlock updates them without restarting the audio. */
    private static final Map<BlockPos, JukeboxSettings> SETTINGS = new HashMap<>();
    private static final Map<Side, Group> GROUPS = new HashMap<>();
    /** The side each emitter block playing here plays. */
    private static final Map<BlockPos, Side> SIDES = new HashMap<>();

    /**
     * One side (see Streams.Channel) of the track of the source at {@code source}. Every emitter playing it here is
     * heard through one stream, so a source takes at most 3 sound channels (its mix, left and right) however many
     * jukeboxes, speakers and displays play it: one each was an OpenAL source each, and a wall of speakers froze the
     * game waiting on the sound thread.
     */
    private record Side(BlockPos source, Streams.Channel channel) {
    }

    /** The emitters playing one side, and how they sound together as of this tick (see update). */
    private static final class Group {
        final String url;
        /** Which load of the source's track they play (see Streams.Load); 0 if unknown. */
        final long load;
        /** When the track's top was as the first of them asked (System.nanoTime), to tell a restart (see joins). */
        final long origin;
        /** Each emitter's block, and how it sounds. */
        final Map<BlockPos, Streams.Sound> emitters = new LinkedHashMap<>();
        boolean present;
        boolean nearby;
        double gain;
        @Nullable
        Vec3 at;
        /** How wide it is heard (see Mix#radius). */
        double radius;
        /** The stream last given a radius (a restarted one is a new OpenAL source), and that radius. */
        @Nullable
        private StreamSound spreadOn;
        private double spreadRadius;

        Group(String url, long load, long origin) {
            this.url = url;
            this.load = load;
            this.origin = origin;
        }

        /**
         * Whether an emitter asked to play {@code url}'s {@code load} from its top at {@code origin} joins this
         * playback: by the rule its native player is shared by (see NativeAudio.Shared#join), so the two never
         * disagree. A known load decides alone: while a track loads, every emitter of it says 0 s.
         */
        boolean joins(String url, long load, double startSeconds, long origin) {
            if (!this.url.equals(url)) {
                return false;
            }
            if (load != 0 && this.load != 0) {
                return load == this.load;
            }
            return startSeconds > 0 || Math.abs(origin - this.origin) / 1e9 < SyncClock.SAME_PLAYBACK_SECONDS;
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
                if (!blockPresent(pos)) {
                    continue;
                }
                present = true;
                nearby |= inRange(pos, sound.origin());
                Vec3 ear = toBlockSpace(pos, camera);
                mix.add(world, settings(pos).gain(pos, sound.origin(), ear) * sound.directivity(ear));
            }
            gain = mix.gain();
            Vec3 heard = mix.at();
            at = heard != null ? heard : first != null ? first : at;
            radius = heard != null ? mix.radius() : 0;
        }

        /**
         * Gives {@code sound}, its stream, the width its emitters are heard over (see Mix#radius), once its channel
         * is there and again as that width changes; each change waits its turn on the sound thread, so not for less.
         */
        void spread(@Nullable StreamSound sound) {
            if (sound == null || sound == spreadOn && Math.abs(radius - spreadRadius) <= 0.05 * Math.max(1, spreadRadius)) {
                return;
            }
            var engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).create_media_systems$soundEngine();
            var handle = ((SoundEngineAccessor) engine).create_media_systems$instanceToChannel().get(sound);
            if (handle == null) {
                return; // not playing yet
            }
            float given = (float) radius;
            spreadOn = sound;
            spreadRadius = given;
            handle.execute(channel -> {
                // Without it (not OpenAL Soft), a point between them, as heard from the listener.
                if (AL.getCapabilities().AL_EXT_SOURCE_RADIUS) {
                    AL10.alSourcef(((ChannelAccessor) channel).create_media_systems$source(),
                            EXTSourceRadius.AL_SOURCE_RADIUS, given);
                }
            });
        }
    }

    /**
     * Emitters of one side, heard from {@code listener} as one stream: as loud as their sound powers summed (they
     * play the same samples, from different places), from the direction they are heard from, weighted by that power,
     * and as wide as they spread around the listener. One speaker is heard where it is, a wall in front over its
     * width, and standing among a side's speakers, from all around. A stereo pair's sides stay two streams.
     */
    static final class Mix {
        private final Vec3 listener;
        private double power;
        /** Power-weighted sums of the directions from the listener (unit vectors) and the distances. */
        private double dx;
        private double dy;
        private double dz;
        private double distance;

        Mix(Vec3 listener) {
            this.listener = listener;
        }

        void add(Vec3 at, double gain) {
            double p = gain * gain;
            Vec3 to = at.subtract(listener);
            double length = to.length();
            power += p;
            distance += p * length;
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
         * Where to play it from: their mean direction, as far as they are on average. Null while none of them is
         * heard; at the listener when they are evenly all around.
         */
        @Nullable
        Vec3 at() {
            if (power <= 0) {
                return null;
            }
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double far = distance / power;
            return length < 1e-6 ? listener : listener.add(dx / length * far, dy / length * far, dz / length * far);
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
            double far = power > 0 ? distance / power : 0;
            Vec3 at = at();
            double d = at == null ? 0 : at.distanceTo(listener);
            if (d < 1e-3) {
                return Math.max(far, 1); // at the listener: any radius is all around
            }
            return spread <= Math.PI ? d * Math.sin(spread / 2) : d * Math.PI / Math.max(2 * Math.PI - spread, Math.PI / 16);
        }
    }

    private ClientStreams() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> {
            Vlc.preload();
            Tools.preload();
            if (ClientConfig.YOUTUBE_COOKIES.get()) {
                CookieReceiver.start();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> ExtensionUrls.refresh());
        NeoForge.EVENT_BUS.addListener(ClientStreams::muteVanillaJukeboxSound);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> GROUPS.forEach((side, group) -> {
            group.update();
            group.spread(PLAYING.get(side));
        }));
        NeoForge.EVENT_BUS.addListener(ClientStreams::releaseFinished);
        NeoForge.EVENT_BUS.addListener(ClientStreams::resolveNext);
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
        SETTINGS.put(pos, payload.settings());
        Side side = new Side(sound.source(), sound.channel());
        Side was = SIDES.put(pos, side);
        if (was != null && !was.equals(side)) {
            leave(was, pos);
        }
        Group group = GROUPS.get(side);
        long origin = System.nanoTime() - Math.round(payload.startSeconds() * 1e9);
        if (group != null && group.joins(payload.url(), payload.load(), payload.startSeconds(), origin)) {
            group.emitters.put(pos, sound);
            return;
        }
        Group fresh = new Group(payload.url(), payload.load(), origin);
        if (group != null) {
            fresh.emitters.putAll(group.emitters);
        }
        fresh.emitters.put(pos, sound);
        fresh.update();
        GROUPS.put(side, fresh);
        payload.video().ifPresent(video -> video.next().ifPresent(next -> MediaSources.fetchAhead(next, video)));
        play(side, new StreamSound(apiStream(payload.url(), payload.startSeconds(), side.channel()), payload.url(),
                side.source(), payload.load(), payload.startSeconds(), SoundSource.RECORDS, () -> fresh.at,
                () -> fresh.present, () -> fresh.nearby, () -> fresh.gain, payload.video().orElse(null), side.channel(),
                // Through any one of its emitters: the server tells the source's listeners, not each emitter's.
                seconds -> Minecraft.getInstance().execute(() -> fresh.emitters.keySet().stream().findFirst()
                        .ifPresent(at -> PacketDistributor.sendToServer(new StreamPackets.StartedAtBlock(at, (float) seconds))))));
    }

    /** The emitter at {@code pos} no longer plays {@code side}; the side stops with its last emitter. */
    private static void leave(Side side, BlockPos pos) {
        Group group = GROUPS.get(side);
        if (group != null && group.emitters.remove(pos) != null && group.emitters.isEmpty()) {
            GROUPS.remove(side);
            StreamSound sound = PLAYING.remove(side);
            if (sound != null) {
                Minecraft.getInstance().getSoundManager().stop(sound);
                sound.release();
            }
        }
    }

    /** The API's stream of {@code url} from {@code startSeconds} in, plus the offset asked for. */
    public static DoubleFunction<String> apiStream(String url, double startSeconds, Streams.Channel channel) {
        return offset -> UrlPolicy.streamUrl(ServerConfig.API_URL.get(), url, startSeconds + offset, channel.api);
    }

    public static void retune(StreamPackets.RetuneAtBlock payload) {
        SETTINGS.put(payload.pos(), payload.settings());
    }

    public static void sync(StreamPackets.SyncAtBlock payload) {
        Side side = SIDES.get(payload.pos());
        StreamSound sound = side == null ? null : PLAYING.get(side);
        var mc = Minecraft.getInstance();
        if (sound != null && mc.player != null && mc.getConnection() != null) {
            // The server's position as it sent this; it is half our ping further on by now.
            PlayerInfo self = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            sound.sync(payload.seconds() + (self == null ? 0 : self.getLatency() / 2000.0));
        }
    }

    public static void stop(StreamPackets.StopAtBlock payload) {
        SETTINGS.remove(payload.pos());
        Side side = SIDES.remove(payload.pos());
        if (side != null) {
            leave(side, payload.pos());
        }
    }

    static long position(BlockPos source, String track) {
        for (StreamSound sound : PLAYING.values()) {
            long position = sound.position(source, track);
            if (position >= 0) {
                return position;
            }
        }
        return -1;
    }

    /** Plays {@code sound} on {@code channel}, instead of what played there. */
    public static void play(Object channel, StreamSound sound) {
        var sounds = Minecraft.getInstance().getSoundManager();
        StreamSound previous = PLAYING.put(channel, sound);
        if (previous != null) {
            sounds.stop(previous);
            previous.release();
        }
        sounds.play(sound);
    }

    /** A playing stream broke off: posted to chat, unless {@code sound} was already replaced or stopped. */
    static void interrupted(StreamSound sound, String reason) {
        Minecraft.getInstance().execute(() -> {
            if (PLAYING.containsValue(sound)) {
                Reports.show(Component.translatable("create_media_systems.stream.interrupted", reason), false);
            }
        });
    }

    private static JukeboxSettings settings(BlockPos pos) {
        return SETTINGS.getOrDefault(pos, JukeboxSettings.DEFAULT);
    }

    /**
     * In the last minute of each video playing here, resolves the next on its Blu-ray player, unless it is kept
     * already: it then starts without waiting on yt-dlp, which takes its own time on every computer, and the
     * listeners all have it loaded about as soon as each other (see Streams#played).
     */
    private static void resolveNext(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.getGameTime() % 20 != 0) {
            return;
        }
        for (StreamSound sound : PLAYING.values()) {
            Streams.Video video = sound.video();
            if (video != null && video.next().isPresent()) {
                double left = NativeAudio.remaining(video.player());
                if (left >= 0 && left <= MediaSources.RESOLVE_AHEAD_SECONDS) {
                    MediaSources.resolveAhead(video.next().get(), video);
                }
            }
        }
    }

    /** Covers every way a sound ends (stopped, finished, culled, disconnect), including before it started. */
    private static void releaseFinished(ClientTickEvent.Post event) {
        var sounds = Minecraft.getInstance().getSoundManager();
        Map.copyOf(PLAYING).forEach((channel, sound) -> {
            if (sounds.isActive(sound)) {
                return;
            }
            if (sound.wantsRestart()) {
                LogUtils.getLogger().info("The sound engine dropped CMS stream {} as its audio ran dry; starting it again", channel);
                play(channel, sound.again());
            } else if (sound.waitingForChannel() && sound.retryChannelNow()) {
                // Never started, so the same sound: it still joins its playback where it is now (see NativeAudio).
                sounds.play(sound);
            }
        });
        PLAYING.entrySet().removeIf(entry -> {
            boolean finished = !sounds.isActive(entry.getValue()) && !entry.getValue().waitingForChannel();
            if (finished) {
                entry.getValue().release();
                Group group = entry.getKey() instanceof Side side ? GROUPS.remove(side) : null;
                if (group != null) {
                    group.emitters.keySet().forEach(pos -> {
                        SIDES.remove(pos, entry.getKey());
                        SETTINGS.remove(pos);
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

    /** Within the range past which the server stops tracking us (it resends on return). */
    private static boolean inRange(BlockPos pos, Vec3 origin) {
        var player = Minecraft.getInstance().player;
        return player != null
                && settings(pos).inRange(pos, origin, toBlockSpace(pos, player.position()), JukeboxSettings.DROP_MARGIN);
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
                && event.getSound().getLocation().equals(CreateMediaSystems.STREAM.getId())) {
            event.setSound(null);
        }
    }
}
