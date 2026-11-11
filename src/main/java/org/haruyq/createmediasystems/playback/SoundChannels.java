package org.haruyq.createmediasystems.playback;

import java.util.function.IntConsumer;

import org.haruyq.createmediasystems.playback.mixin.ChannelAccessor;
import org.haruyq.createmediasystems.playback.mixin.SoundEngineAccessor;
import org.haruyq.createmediasystems.playback.mixin.SoundManagerAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;

/** The sound engine's parts CMS's streams reach, through the mixin accessors: a sound's OpenAL source, its thread. */
final class SoundChannels {
    private SoundChannels() {
    }

    /** Whether {@code sound} plays on a channel yet. */
    static boolean playing(SoundInstance sound) {
        return channel(sound) != null;
    }

    /** Hands {@code sound}'s OpenAL source to {@code action} on the sound thread; false (nothing run) if it has none. */
    static boolean withSource(SoundInstance sound, IntConsumer action) {
        ChannelAccess.ChannelHandle handle = channel(sound);
        if (handle == null) {
            return false;
        }
        handle.execute(channel -> action.accept(((ChannelAccessor) channel).create_media_systems$source()));
        return true;
    }

    static void execute(Runnable task) {
        ((SoundEngineAccessor) engine()).create_media_systems$executor().execute(task);
    }

    private static ChannelAccess.ChannelHandle channel(SoundInstance sound) {
        return ((SoundEngineAccessor) engine()).create_media_systems$instanceToChannel().get(sound);
    }

    private static SoundEngine engine() {
        return ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).create_media_systems$soundEngine();
    }
}
