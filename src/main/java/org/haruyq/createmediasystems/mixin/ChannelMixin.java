package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.playback.CmsStream;
import org.haruyq.createmediasystems.playback.DeviceClock;
import org.haruyq.createmediasystems.playback.StreamPump;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.SOFTSourceStartDelay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.sounds.AudioStream;

/**
 * Starts CMS's streams (see CmsStream) when they ask to (see DeviceClock), so the speakers of one playback start
 * together, and has StreamPump refill them.
 */
@Mixin(Channel.class)
abstract class ChannelMixin {
    @Shadow
    @Final
    private int source;
    @Unique
    private long create_media_systems$startAt;
    @Shadow
    private AudioStream stream;

    @Shadow
    protected abstract int removeProcessedBuffers();

    @Shadow
    public abstract void attachBufferStream(AudioStream stream);

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void create_media_systems$attach(AudioStream stream, CallbackInfo ci) {
        if (stream instanceof CmsStream cms) {
            create_media_systems$startAt = cms.attach(source, DeviceClock.now());
            StreamPump.add((Channel) (Object) this);
        } else {
            create_media_systems$startAt = 0;
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void create_media_systems$destroy(CallbackInfo ci) {
        StreamPump.remove((Channel) (Object) this);
    }

    @WrapOperation(method = {"play", "unpause"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alSourcePlay(I)V"))
    private void create_media_systems$playOnTime(int source, Operation<Void> original) {
        if (stream instanceof CmsStream cms && cms.advancesWhilePaused()
                && (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED
                || create_media_systems$startAt > 0 && DeviceClock.now() >= create_media_systems$startAt)) {
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_INITIAL) {
                // Stop only releases queued buffers after a source has entered PLAYING.
                SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(source, Long.MAX_VALUE);
            }
            AL10.alSourceStop(source);
            removeProcessedBuffers();
            attachBufferStream(stream);
        }
        if (create_media_systems$startAt > 0) {
            SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(source, create_media_systems$startAt);
        } else {
            original.call(source);
        }
    }
}
