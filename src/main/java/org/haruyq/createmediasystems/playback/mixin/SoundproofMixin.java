package org.haruyq.createmediasystems.playback.mixin;

import org.haruyq.createmediasystems.blocks.soundproof.client.SoundproofClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

/**
 * Plays the mod's sounds as loud as what soundproofs between them and the listener lets through (see
 * SoundproofClient), as they start and every tick after. One place for all of them: each sets its own volume.
 */
@Mixin(SoundEngine.class)
abstract class SoundproofMixin {
    @ModifyExpressionValue(method = "play", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float create_media_systems$soundproofedStart(float volume, @Local(argsOnly = true) SoundInstance sound) {
        return create_media_systems$heard(volume, sound);
    }

    @ModifyReturnValue(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN"))
    private float create_media_systems$soundproofed(float volume, @Local(argsOnly = true) SoundInstance sound) {
        return create_media_systems$heard(volume, sound);
    }

    /** Never 0 for a sound that was not: a channel at 0 is stopped when its category's volume changes. */
    @Unique
    private static float create_media_systems$heard(float volume, SoundInstance sound) {
        float heard = volume * SoundproofClient.gain(sound);
        return heard > 0 || volume <= 0 ? heard : Float.MIN_VALUE;
    }
}
