package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.stream.StreamFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

/**
 * Keeps CMS's streams playing, silently, at 0% volume. Vanilla drops a sound when the master volume is 0 or stops
 * its channel when its category's is, but a stream's playback (the native player, the video on its displays, the
 * server waiting for a listener to load it) only starts once the engine opens it, so neither video nor sound started.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    private static boolean create_media_systems$stream(SoundInstance sound) {
        return StreamFeature.STREAM.getId().equals(sound.getLocation());
    }

    @ModifyExpressionValue(method = "play", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Listener;getGain()F"))
    private float create_media_systems$playAtZeroMaster(float gain, @Local(argsOnly = true) SoundInstance sound) {
        return gain <= 0 && create_media_systems$stream(sound) ? 1 : gain;
    }

    @ModifyExpressionValue(method = "tickNonPaused", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Options;getSoundSourceVolume(Lnet/minecraft/sounds/SoundSource;)F"))
    private float create_media_systems$keepAtZeroCategory(float volume, @Local SoundInstance sound) {
        return volume <= 0 && create_media_systems$stream(sound) ? 1 : volume;
    }

    /** Lowering a category to 0 stops every channel whose volume this gives as 0 (updateCategoryVolume). */
    @ModifyReturnValue(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN"))
    private float create_media_systems$neverZero(float volume, @Local(argsOnly = true) SoundInstance sound) {
        return volume <= 0 && create_media_systems$stream(sound) ? Float.MIN_VALUE : volume;
    }
}
