package org.haruyq.createmediasystems.playback;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;

import org.haruyq.createmediasystems.stream.StreamFeature;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.SOFTDeferredUpdates;

import com.mojang.logging.LogUtils;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Occlusion through blocks and reverb for CMS's jukebox and speaker streams, via Sound Physics Remastered.
 * <p>
 * Sound Physics evaluates a sound when it starts, but skips the RECORDS category (ours) unless its
 * {@code update_moving_sounds} option is on, and a long stream needs re-evaluating as the listener moves anyway.
 * So CMS asks it directly, every {@link #INTERVAL_TICKS}, for its own streams only. They are passed as BLOCKS,
 * which gets past that skip and makes Sound Physics trace from inside the source block, as for a block's sound.
 * Called by reflection: no dependency, and nothing happens without Sound Physics.
 * <p>
 * Sound Physics also moves a sound to where its echoes seem to come from ({@code sound_direction_evaluation}), and
 * that is put back: a speaker is heard where it stands. Moved, a left and a right speaker in one room were both heard
 * from the same direction (an opening, the room's middle), which blended the two sides, and between evaluations the
 * game put each back, so they jumped to and fro.
 * <p>
 * A side of a source plays as one stream from among its speakers (see ClientStreams.Mix), not at any of them, so it
 * is traced from its loudest speaker's block, which Sound Physics leaves out of the trace as the sound's own.
 */
public final class SoundPhysicsCompat {
    /** Sound Physics' own default {@code sound_update_interval}. */
    static final int INTERVAL_TICKS = 5;

    @Nullable
    private static final MethodHandle PROCESS_SOUND = ModList.get().isLoaded("sound_physics_remastered") ? find() : null;
    private static volatile boolean failed;

    private final AtomicReference<Request> pending = new AtomicReference<>();

    /** Trace from {@code from}, play at {@code at}. */
    record Request(Vec3 from, Vec3 at) {
    }

    SoundPhysicsCompat() {
    }

    @Nullable
    private static MethodHandle find() {
        try {
            Class<?> soundPhysics = Class.forName("com.sonicether.soundphysics.SoundPhysics");
            return MethodHandles.publicLookup().findStatic(soundPhysics, "processSound",
                    MethodType.methodType(Vec3.class, int.class, double.class, double.class, double.class,
                            SoundSource.class, ResourceLocation.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("Sound Physics Remastered is installed but its API changed; CMS streams stay dry", e);
            return null;
        }
    }

    static boolean available() {
        return PROCESS_SOUND != null && !failed;
    }

    /**
     * Re-evaluates {@code sound}, traced from {@code tracedFrom} and playing at {@code playedAt}, on the sound thread like Sound
     * Physics itself does.
     */
    void update(SoundInstance sound, Vec3 tracedFrom, Vec3 playedAt) {
        if (!SoundChannels.playing(sound) || !request(new Request(tracedFrom, playedAt))) {
            return;
        }
        SoundChannels.withSource(sound, source -> {
            Request request = take();
            Vec3 at = request.at();
            // Sound Physics moves the source to where its echoes seem to come from midway through tracing, then sets
            // its filters one by one; OpenAL mixing in between played the speaker from there for a moment (more often
            // the slower tracing ran, as when frames drop), a crackle. Deferred, all of it lands in one mix, put back.
            boolean defer = AL.getCapabilities().AL_SOFT_deferred_updates;
            if (defer) {
                SOFTDeferredUpdates.alDeferUpdatesSOFT();
            }
            try {
                Vec3 from = request.from();
                Object ignored = PROCESS_SOUND.invoke(source, from.x, from.y, from.z, SoundSource.BLOCKS,
                        StreamFeature.STREAM.getId());
                AL10.alSource3f(source, AL10.AL_POSITION, (float) at.x, (float) at.y, (float) at.z);
            } catch (Throwable e) {
                failed = true;
                LogUtils.getLogger().warn("Sound Physics Remastered failed on a CMS stream; turning it off for CMS", e);
            } finally {
                if (defer) {
                    SOFTDeferredUpdates.alProcessUpdatesSOFT();
                }
            }
        });
    }

    /** One waiting evaluation per stream, at its latest position if the sound thread falls behind. */
    boolean request(Request request) {
        return pending.getAndSet(request) == null;
    }

    Request take() {
        return pending.getAndSet(null);
    }
}
