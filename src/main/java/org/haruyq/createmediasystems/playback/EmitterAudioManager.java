package org.haruyq.createmediasystems.playback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.stream.Portable;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Plays the music emitters near this listener (see MusicEmitter): the nearest of every kind together, up to one budget
 * (ClientConfig.MAX_AUDIBLE_DISC_PROJECTIONS), each from where it is and pitched by its Doppler shift for this
 * listener. A link streams (see StreamSound), a jukebox record plays its own song event (see EmitterRecordSound); both
 * open where the emitter's server clock is, and start over there once a still emitter's sound drifts off it.
 */
public final class EmitterAudioManager {
    /** Ticks a playback has to load before it counts as dropped or drifted. */
    static final int GRACE_TICKS = 40;
    /** How often (ticks) it looks for emitters come in range; where they play from and their pitch follow every tick. */
    private static final int FIND_TICKS = 5;
    private static final List<Function<Entity, Stream<? extends MusicEmitter>>> FINDERS = new ArrayList<>();
    private static final Map<UUID, Playing> PLAYING = new HashMap<>();
    private static int ticks;

    /** How one emitter is heard here now, for diagnostics; seconds are -1 where unknown. */
    public record Heard(UUID id, int revision, double startSeconds, double serverSeconds, double audibleSeconds,
                        boolean stream, double requestedSeconds, double pitch, double gain) {
    }

    /** One emitter playing here: as it was when it started ({@code revision}, at {@code startedAt}), on one of the two. */
    private record Playing(MusicEmitter emitter, int revision, int startedAt, double startSeconds,
                           @Nullable ClientStreams.Slot stream, @Nullable EmitterRecordSound record) {
        /** Still playing, loading, or waiting for a channel. */
        boolean active() {
            return stream != null ? stream.playing() : Minecraft.getInstance().getSoundManager().isActive(record);
        }

        double audibleSeconds() {
            return stream != null ? stream.audibleSeconds() : record.audibleSeconds();
        }

        void stop() {
            if (stream != null) {
                stream.stop();
            } else {
                Minecraft.getInstance().getSoundManager().stop(record);
            }
        }
    }

    private EmitterAudioManager() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(EmitterAudioManager::tick);
        // A reload or device change destroys the old OpenAL channels: forget them, so the emitters near are found and
        // lined up with their clocks again.
        modBus.addListener((SoundEngineLoadEvent event) -> Minecraft.getInstance().execute(EmitterAudioManager::stopAll));
    }

    /** Adds the emitters of one kind (launched discs, PartyBoxes, megaphones) that {@code finder} finds near a camera. */
    public static void add(Function<Entity, Stream<? extends MusicEmitter>> finder) {
        FINDERS.add(finder);
    }

    /** The emitter playing here as {@code id}, as it started; null if none. */
    @Nullable
    public static MusicEmitter playing(UUID id) {
        Playing playing = PLAYING.get(id);
        return playing == null ? null : playing.emitter;
    }

    /** How the emitter {@code id} is heard here now; null if it isn't played here. */
    @Nullable
    public static Heard heard(UUID id) {
        Playing p = PLAYING.get(id);
        return p == null ? null : new Heard(id, p.revision, p.startSeconds, p.emitter.seconds(), p.audibleSeconds(),
                p.stream != null, p.stream != null ? p.stream.requestedSeconds() : -1, pitch(p.emitter), gain(p.emitter));
    }

    /** Every emitter played here, as {@link #heard(UUID)}. */
    public static List<Heard> heard() {
        return PLAYING.keySet().stream().map(EmitterAudioManager::heard).toList();
    }

    /** Whether {@code sound} is a music emitter's record (its link plays as a StreamSound). */
    public static boolean plays(SoundInstance sound) {
        return sound instanceof EmitterRecordSound;
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Entity ear = mc.getCameraEntity();
        if (mc.level == null || mc.player == null || ear == null) {
            stopAll();
            return;
        }
        ticks++;
        PLAYING.values().removeIf(p -> {
            int age = ticks - p.startedAt;
            if (heard(p.emitter, p.revision) && (p.active() || age < GRACE_TICKS)
                    && !realign(p.audibleSeconds(), p.emitter.seconds(), age, pitch(p.emitter))) {
                return false;
            }
            p.stop();
            return true;
        });
        if (ticks % FIND_TICKS != 0) {
            return;
        }
        List<MusicEmitter> nearest = FINDERS.stream().<MusicEmitter>flatMap(finder -> finder.apply(ear))
                .filter(emitter -> emitter.present() && inRange(emitter, ear))
                .sorted(Comparator.comparingDouble((MusicEmitter emitter) -> emitter.position().distanceToSqr(ear.position()))
                        .thenComparing(MusicEmitter::id))
                .limit(ClientConfig.MAX_AUDIBLE_DISC_PROJECTIONS.get()).toList();
        Set<UUID> chosen = nearest.stream().map(MusicEmitter::id).collect(Collectors.toSet());
        PLAYING.values().removeIf(p -> {
            if (chosen.contains(p.emitter.id())) {
                return false;
            }
            p.stop();
            return true;
        });
        for (MusicEmitter emitter : nearest) {
            if (!PLAYING.containsKey(emitter.id())) {
                Playing playing = start(mc, emitter);
                if (playing != null) {
                    PLAYING.put(emitter.id(), playing);
                }
            }
        }
    }

    /** Plays only what {@code emitter} holds (see MediaItem): its link if allowed, else its record; null for neither. */
    @Nullable
    private static Playing start(Minecraft mc, MusicEmitter emitter) {
        int revision = emitter.revision();
        double at = emitter.seconds();
        ItemStack media = emitter.media();
        String url = MediaItem.url(media);
        if (url != null && UrlPolicy.isAllowed(url)) {
            var heard = new StreamSpec.Emitter(emitter::position, null,
                    () -> emitter.present() && emitter.revision() == revision, () -> inRange(emitter),
                    () -> gain(emitter), () -> pitch(emitter));
            StreamSpec spec = new StreamSpec(url, at, Streams.Channel.MIX, SoundSource.RECORDS, heard,
                    seconds -> tellLength(mc, emitter, revision, seconds)).movingDisc(emitter.id(), emitter::seconds);
            for (var decorator : emitter.decorators()) {
                spec = spec.decorated(decorator);
            }
            ClientStreams.Slot slot = new ClientStreams.Slot("emitter " + emitter.id());
            slot.play(new StreamSound(emitter.viaApi() ? spec.viaApi() : spec));
            return new Playing(emitter, revision, ticks, at, slot, null);
        }
        return MediaItem.song(media, mc.level.registryAccess()).map(song -> {
            var record = new EmitterRecordSound(emitter, revision, song.value().soundEvent().value());
            mc.getSoundManager().play(record);
            return new Playing(emitter, revision, ticks, at, null, record);
        }).orElse(null);
    }

    /** Tells the server the length of the track {@code emitter} plays, as the native player here sees it (see Portable). */
    private static void tellLength(Minecraft mc, MusicEmitter emitter, int revision, double seconds) {
        if (Double.isFinite(seconds) && seconds > 0) {
            mc.execute(() -> {
                if (mc.getConnection() != null && emitter.present() && emitter.revision() == revision) {
                    PacketDistributor.sendToServer(new Portable.Length(emitter.id(), revision, seconds));
                }
            });
        }
    }

    private static void stopAll() {
        PLAYING.values().forEach(Playing::stop);
        PLAYING.clear();
    }

    /** Whether {@code emitter} is still to be heard here as it was at {@code revision}. */
    static boolean heard(MusicEmitter emitter, int revision) {
        return emitter.revision() == revision && emitter.present() && inRange(emitter);
    }

    private static boolean inRange(MusicEmitter emitter) {
        Entity ear = Minecraft.getInstance().getCameraEntity();
        return ear != null && inRange(emitter, ear);
    }

    private static boolean inRange(MusicEmitter emitter, Entity ear) {
        return emitter.position().distanceToSqr(ear.position()) <= emitter.range() * emitter.range();
    }

    static double gain(MusicEmitter emitter) {
        Entity ear = Minecraft.getInstance().getCameraEntity();
        return ear == null ? 0 : emitter.gain(ear);
    }

    /** Physical Doppler of the emitter's music for this listener: 1 while either stands still or jumped. */
    static double pitch(MusicEmitter emitter) {
        Entity ear = Minecraft.getInstance().getCameraEntity();
        Vec3 velocity = emitter.velocity();
        Vec3 earVelocity = ear == null || ear.tickCount < 2 ? null : DopplerMath.step(ear);
        if (velocity == null || earVelocity == null) {
            return 1;
        }
        return DopplerMath.ratio(emitter.position(), velocity, new Vec3(ear.getX(), ear.getEyeY(), ear.getZ()), earVelocity);
    }

    /**
     * Whether to start a playback over where its server clock is: once loaded ({@code age} ticks), its {@code audible}
     * position is off the {@code server}'s by more than a quarter second while the emitter's {@code doppler} is about 1.
     * A moving emitter's music runs at its Doppler pitch, ahead of or behind its clock, as it should, and stays.
     */
    static boolean realign(double audible, double server, int age, double doppler) {
        return age >= GRACE_TICKS && Math.abs(doppler - 1) <= .01 && Double.isFinite(audible) && audible >= 0
                && Double.isFinite(server) && Math.abs(audible - server) > .25;
    }
}
