package org.haruyq.createmediasystems.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/** Reaches a playing sound's OpenAL source, for Sound Physics Remastered (see SoundPhysicsCompat). */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
    @Accessor("soundEngine")
    SoundEngine create_media_systems$soundEngine();
}
