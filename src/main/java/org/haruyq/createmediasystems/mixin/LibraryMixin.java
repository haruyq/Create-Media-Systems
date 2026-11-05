package org.haruyq.createmediasystems.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Library;

/**
 * More streaming sound channels. Vanilla keeps √(all channels), at most 8, which CMS fills on its own: every
 * speaker is a stream, so 8 speakers took all of them, and restarting them (a new track, a seek) briefly needs
 * twice as many, as the old ones are freed a moment later, so the new ones failed and the speakers went silent.
 */
@Mixin(Library.class)
abstract class LibraryMixin {
    @ModifyExpressionValue(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I",
            ordinal = 0))
    private int create_media_systems$moreStreams(int vanilla, @Local(ordinal = 0) int channels) {
        // Half of all channels (about 127 of OpenAL Soft's 255), for walls of speakers; the other half still covers
        // far more ordinary sounds than play at once.
        return Math.max(vanilla, channels / 2);
    }
}
