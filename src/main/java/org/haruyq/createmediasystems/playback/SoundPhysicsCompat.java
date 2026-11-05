package org.haruyq.createmediasystems.playback;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.mixin.ChannelAccessor;
import org.haruyq.createmediasystems.mixin.SoundEngineAccessor;
import org.haruyq.createmediasystems.mixin.SoundManagerAccessor;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
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
 */
public final class SoundPhysicsCompat {
    /** Sound Physics' own default {@code sound_update_interval}. */
    static final int INTERVAL_TICKS = 5;

    @Nullable
    private static final MethodHandle PROCESS_SOUND = ModList.get().isLoaded("sound_physics_remastered") ? find() : null;
    private static volatile boolean failed;

    private final AtomicReference<Vec3> pending = new AtomicReference<>();

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

    /** Re-evaluates {@code sound}, playing at {@code position}, on the sound thread like Sound Physics itself does. */
    void update(SoundInstance sound, Vec3 position) {
        var engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).create_media_systems$soundEngine();
        var handle = ((SoundEngineAccessor) engine).create_media_systems$instanceToChannel().get(sound);
        if (handle == null || !request(position)) {
            return;
        }
        handle.execute(channel -> {
            Vec3 at = take();
            try {
                int source = ((ChannelAccessor) channel).create_media_systems$source();
                Object ignored = PROCESS_SOUND.invoke(source, at.x, at.y, at.z, SoundSource.BLOCKS,
                        CreateMediaSystems.STREAM.getId());
                AL10.alSource3f(source, AL10.AL_POSITION, (float) at.x, (float) at.y, (float) at.z);
            } catch (Throwable e) {
                failed = true;
                LogUtils.getLogger().warn("Sound Physics Remastered failed on a CMS stream; turning it off for CMS", e);
            }
        });
    }

    /** One waiting evaluation per stream, at its latest position if the sound thread falls behind. */
    boolean request(Vec3 position) {
        return pending.getAndSet(position) == null;
    }

    Vec3 take() {
        return pending.getAndSet(null);
    }
}
