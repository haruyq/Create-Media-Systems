package org.haruyq.createmediasystems.launcher.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.launcher.DiscProjectile;
import org.haruyq.createmediasystems.launcher.DopplerMath;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.haruyq.createmediasystems.playback.PlaybackSync;
import org.haruyq.createmediasystems.stream.Streams;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;

/**
 * One genuine source per physical disc. Each listener computes its own pitch
 * from relative positions/velocities, so multiplayer does not share an incorrect
 * pre-baked Doppler tone. The sound follows the CD before AND after impact.
 *
 * URL music uses the existing CMS mono AudioStream/OpenAL path. Vanilla and
 * data-pack records use a streaming stereo->mono adapter and the real song event.
 */
public final class DiscAudioClient {
    private static final double HEAR_DISTANCE = 72.0;
    private static final Map<UUID, Playing> PLAYING = new HashMap<>();
    public static double audibleSeconds(UUID id){var p=PLAYING.get(id);return p==null?-1:p.vanilla==null?ClientStreams.audibleSeconds(p.channel):p.vanilla.audibleSeconds();}
    private static int tickCounter;

    private record Playing(DiscProjectile disc, Object channel, FlyingJukeboxSound vanilla, int startedTick) {}

    private DiscAudioClient() {}

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(DiscAudioClient::onTick);
        modBus.addListener(DiscAudioClient::onSoundEngineLoad);
    }

    private static void onSoundEngineLoad(SoundEngineLoadEvent event) {
        // Reload/device changes destroy the old OpenAL channels. Forget those
        // instances so nearby physical discs are discovered and seek again.
        Minecraft.getInstance().execute(DiscAudioClient::stopAll);
    }

    private static void onTick(ClientTickEvent.Post ignored) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopAll();
            return;
        }
        for(Entity entity:mc.level.entitiesForRendering())if(entity instanceof DiscProjectile disc)disc.refreshAttachedPose();
        PLAYING.entrySet().removeIf(entry -> {
            Playing playing = entry.getValue();
            boolean managed = (playing.channel == null ? mc.getSoundManager().isActive(playing.vanilla) : ClientStreams.contains(playing.channel))
                    || tickCounter - playing.startedTick < 40;
            if (playing.disc.level() == mc.level && !playing.disc.isRemoved() && inRange(playing.disc) && managed
                    && !PlaybackSync.realign(audibleSeconds(entry.getKey()),playing.disc.elapsedMusicSeconds(),tickCounter-playing.startedTick,pitchAt(playing.disc))) {
                return false;
            }
            stop(playing);
            return true;
        });
        // Discovery every 5 ticks; position and Doppler are recalculated every AUDIO tick.
        if (++tickCounter % 5 != 0) return;
        Entity camera = mc.getCameraEntity();
        if (camera == null) return;
        List<DiscProjectile> audible = mc.level.getEntitiesOfClass(DiscProjectile.class,
                camera.getBoundingBox().inflate(HEAR_DISTANCE)).stream()
                .filter(disc -> !disc.isRemoved() && inRange(disc))
                .sorted(Comparator.comparingDouble((DiscProjectile disc) -> disc.distanceToSqr(camera))
                        .thenComparing(DiscProjectile::getUUID))
                .limit(ClientConfig.MAX_AUDIBLE_DISC_PROJECTIONS.get()).toList();
        Set<UUID> selected = audible.stream().map(DiscProjectile::getUUID).collect(Collectors.toSet());
        PLAYING.entrySet().removeIf(entry -> {
            if (selected.contains(entry.getKey())) return false;
            stop(entry.getValue());
            return true;
        });
        for (DiscProjectile disc : audible) {
            if (!disc.isRemoved() && inRange(disc) && !PLAYING.containsKey(disc.getUUID())) {
                Playing playing = start(mc, disc);
                if (playing != null) PLAYING.put(disc.getUUID(), playing);
            }
        }
    }

    /** Select only sound stored ON the actual disc ItemStack, never an arbitrary weapon sound. */
    private static String mediaUrl(ItemStack disc) {
        if (disc.is(AudioFeature.CUSTOM_DISC.get())) {
            return disc.get(AudioFeature.URL.get());
        }
        if (disc.is(VideoFeature.DVD.get()) || disc.is(VideoFeature.BLU_RAY.get())) {
            List<String> playlist = disc.getOrDefault(VideoFeature.PLAYLIST.get(), List.of());
            return playlist.isEmpty() ? null : playlist.get(0);
        }
        return null;
    }

    private static Playing start(Minecraft mc, DiscProjectile disc) {
        ItemStack item = disc.getItem();
        String url = mediaUrl(item);
        if (url != null && !url.isBlank() && ServerConfig.isAllowed(url)) {
            // Do not synthesize any alternate Doppler sound. This StreamSound
            // fetches the disc's track, follows the entity and pitch-shifts ITS PCM.
            double start=disc.elapsedMusicSeconds();
            StreamSound sound = new StreamSound(
                    ClientStreams.apiStream(url, start, Streams.Channel.MIX), url,
                    null, 0L, start, SoundSource.RECORDS,
                    disc::position, null,
                    () -> !disc.isRemoved() && mc.level == disc.level(),
                    () -> inRange(disc), () -> gain(disc),
                    null, Streams.Channel.MIX, seconds -> {
                        if (Double.isFinite(seconds) && seconds > 0) mc.execute(() -> {
                            if (mc.getConnection() != null && mc.level == disc.level() && !disc.isRemoved())
                                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                        new org.haruyq.createmediasystems.launcher.DiscPlaybackMetadata(disc.getId(), seconds));
                        });
                    });
            sound.setDynamicPitch(() -> pitchAt(disc));
            sound.setDiscPlayback(disc.getUUID(), disc::elapsedMusicSeconds);
            Object channel = disc.getUUID();
            ClientStreams.play(channel, sound); // uses CMS's existing interrupted-stream recovery
            return new Playing(disc, channel, null, tickCounter);
        }
        // A normal jukebox disc uses its OWN song event, streamed and downmixed
        // to mono so OpenAL can perform HRTF/directional audio from the moving CD.
        // CMS custom discs without a URL must not play the placeholder song.
        if (item.is(AudioFeature.CUSTOM_DISC.get())
                || item.is(VideoFeature.DVD.get())
                || item.is(VideoFeature.BLU_RAY.get())) return null;
        return JukeboxSong.fromStack(mc.level.registryAccess(), item).map(song -> {
            FlyingJukeboxSound sound = new FlyingJukeboxSound(disc, song.value().soundEvent().value());
            mc.getSoundManager().play(sound);
            return new Playing(disc, null, sound, tickCounter);
        }).orElse(null);
    }

    private static void stop(Playing playing) {
        if (playing.channel != null) ClientStreams.stop(playing.channel);
        if (playing.vanilla != null) Minecraft.getInstance().getSoundManager().stop(playing.vanilla);
    }

    private static void stopAll() {
        PLAYING.values().forEach(DiscAudioClient::stop);
        PLAYING.clear();
    }

    static boolean inRange(DiscProjectile disc) {
        Entity ear = Minecraft.getInstance().getCameraEntity();
        return ear != null && !disc.isRemoved() && disc.soundEnabled() && disc.level() == ear.level()
                && disc.distanceToSqr(ear) <= HEAR_DISTANCE * HEAR_DISTANCE;
    }

    /** Distances use the SAME sound-emitting CD position, with no proxy speaker. */
    static float gain(DiscProjectile disc) {
        Entity ear = Minecraft.getInstance().getCameraEntity();
        if (ear == null) return 0.0f;
        double distance = Math.sqrt(disc.distanceToSqr(ear));
        double scaled = distance / 18.0;
        return (float) Math.max(0.0, Math.min(0.9, 0.9 / (1.0 + scaled * scaled)));
    }

    /** Physical Doppler of the original musical content for this listener. */
    static double pitchAt(DiscProjectile disc) {
        Entity ear = Minecraft.getInstance().getCameraEntity();
        if (ear == null || disc.embedded() && !disc.attached()) return 1.0;
        if(disc.attached()) {
            var target=disc.attachmentTarget();
            var s=target==null?Vec3.ZERO:new Vec3(target.getX()-target.xo,target.getY()-target.yo,target.getZ()-target.zo);
            return DopplerMath.observedRatio(disc.getX(),disc.getY(),disc.getZ(),s.x,s.y,s.z,
                    ear.getX(),ear.getEyeY(),ear.getZ(),ear.getX()-ear.xo,ear.getY()-ear.yo,ear.getZ()-ear.zo);
        }
        var s = disc.getDeltaMovement();
        var l = ear.getDeltaMovement();
        return DopplerMath.ratio(disc.getX(), disc.getY(), disc.getZ(), s.x, s.y, s.z,
                ear.getX(), ear.getEyeY(), ear.getZ(), l.x, l.y, l.z);
    }
}
