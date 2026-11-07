package org.haruyq.createmediasystems.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Library;

/**
 * More streaming sound channels. Vanilla keeps √(all channels), at most 8. Each playing source takes up to 3 (its
 * mix, left and right, see ClientStreams), and restarting them (a new track) briefly needs twice as many, as the old
 * ones are freed a moment later, so a few sources playing at once already ran out and went silent.
 */
@Mixin(Library.class)
abstract class LibraryMixin {
    @ModifyExpressionValue(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I",
            ordinal = 0))
    private int create_media_systems$moreStreams(int vanilla, @Local(ordinal = 0) int channels) {
        // Half of all channels (about 127 of OpenAL Soft's 255); the other half still covers far more ordinary sounds
        // than play at once. Never more sources than OpenAL Soft's default: every playing one costs the sound thread
        // and the mixer, and 4096 of them, one per speaker of a wall, froze the game waiting on the sound thread.
        return Math.max(vanilla, channels / 2);
    }
}
