package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.audio.NoteBlockSounds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(NoteBlock.class)
abstract class NoteBlockMixin {
    @ModifyExpressionValue(method = "triggerEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/properties/NoteBlockInstrument;getSoundEvent()Lnet/minecraft/core/Holder;"))
    private Holder<SoundEvent> create_media_systems$copperInstrument(Holder<SoundEvent> original, BlockState state,
                                                                   Level level, BlockPos pos, int id, int param) {
        return NoteBlockSounds.sound(original, level.getBlockState(pos.below()));
    }
}
