package org.haruyq.createmediasystems.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/** Reaches the sound engine: a playing sound's OpenAL channel and its thread (see playback.SoundChannels). */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
    @Accessor("soundEngine")
    SoundEngine create_media_systems$soundEngine();
}
