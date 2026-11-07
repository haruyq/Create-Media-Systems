package org.haruyq.createmediasystems.mixin;

import java.nio.IntBuffer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

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
    /**
     * OpenAL sources asked for, instead of OpenAL Soft's default 255: half go to streams (see moreStreams), so about
     * 2000 speakers play at once in range. OpenAL Soft makes sources as they are used, so unused ones cost nothing.
     * A user's alsoft.ini {@code sources} setting still wins. A tuning knob.
     */
    @Unique
    private static final int CREATE_MEDIA_SYSTEMS$SOURCES = 4096;

    /** Asks for more sources first in the list, so a later entry (another mod's, if any) still has its way. */
    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"), index = 1)
    private IntBuffer create_media_systems$moreSources(@Nullable IntBuffer attributes) {
        IntBuffer more = BufferUtils.createIntBuffer(2 + (attributes == null ? 1 : attributes.remaining()));
        more.put(ALC11.ALC_MONO_SOURCES).put(CREATE_MEDIA_SYSTEMS$SOURCES);
        if (attributes == null) {
            more.put(0);
        } else {
            more.put(attributes.duplicate());
        }
        return more.flip();
    }

    @ModifyExpressionValue(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I",
            ordinal = 0))
    private int create_media_systems$moreStreams(int vanilla, @Local(ordinal = 0) int channels) {
        // Half of all channels for walls of speakers; the other half (at most 255, vanilla's own cap) still covers
        // far more ordinary sounds than play at once.
        return Math.max(vanilla, channels / 2);
    }
}
