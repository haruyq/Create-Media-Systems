package org.haruyq.createmediasystems.playback;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleFunction;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.client.Reports;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The streams playing on this computer, one per channel: an emitter block's (keyed by its position), or one train
 * disc reader's announcements (keyed by a string); different channels play at the same time.
 */
public final class ClientStreams {
    private static final Map<Object, StreamSound> PLAYING = new HashMap<>();
    /** Live settings per block stream; RetuneAtBlock updates them without restarting the audio. */
    private static final Map<BlockPos, JukeboxSettings> SETTINGS = new HashMap<>();

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
        NeoForge.EVENT_BUS.addListener(ClientStreams::releaseFinished);
        NeoForge.EVENT_BUS.addListener(ClientStreams::resolveNext);
        if (SoundPhysicsCompat.available()) {
            LogUtils.getLogger().info("Sound Physics Remastered found: CMS streams get occlusion and reverb");
        }
    }

    public static void play(StreamPackets.PlayAtBlock payload) {
        BlockPos pos = payload.pos();
        Streams.Sound sound = payload.sound();
        Vec3 origin = sound.origin();
        SETTINGS.put(pos, payload.settings());
        Streams.Channel channel = sound.channel();
        payload.video().ifPresent(video -> video.next().ifPresent(next -> MediaSources.fetchAhead(next, video)));
        play(pos, new StreamSound(apiStream(payload.url(), payload.startSeconds(), channel), payload.url(),
                sound.source(), payload.startSeconds(), SoundSource.RECORDS, () -> toWorld(pos, origin),
                () -> blockPresent(pos), () -> inRange(pos, origin), () -> {
                    Vec3 ear = toBlockSpace(pos, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
                    return settings(pos).gain(pos, origin, ear) * sound.directivity(ear);
                }, payload.video().orElse(null), channel, seconds -> Minecraft.getInstance().execute(
                        () -> PacketDistributor.sendToServer(new StreamPackets.StartedAtBlock(pos, (float) seconds)))));
    }

    /** The API's stream of {@code url} from {@code startSeconds} in, plus the offset asked for. */
    public static DoubleFunction<String> apiStream(String url, double startSeconds, Streams.Channel channel) {
        return offset -> UrlPolicy.streamUrl(ServerConfig.API_URL.get(), url, startSeconds + offset, channel.api);
    }

    public static void retune(StreamPackets.RetuneAtBlock payload) {
        SETTINGS.put(payload.pos(), payload.settings());
    }

    public static void sync(StreamPackets.SyncAtBlock payload) {
        StreamSound sound = PLAYING.get(payload.pos());
        var mc = Minecraft.getInstance();
        if (sound != null && mc.player != null && mc.getConnection() != null) {
            // The server's position as it sent this; it is half our ping further on by now.
            PlayerInfo self = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            sound.sync(payload.seconds() + (self == null ? 0 : self.getLatency() / 2000.0));
        }
    }

    public static void stop(StreamPackets.StopAtBlock payload) {
        StreamSound sound = PLAYING.remove(payload.pos());
        SETTINGS.remove(payload.pos());
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
            sound.release();
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
                if (entry.getKey() instanceof BlockPos pos) {
                    SETTINGS.remove(pos);
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
