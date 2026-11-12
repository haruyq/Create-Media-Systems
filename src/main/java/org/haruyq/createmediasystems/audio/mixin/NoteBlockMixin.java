package org.haruyq.createmediasystems.audio.mixin;

import org.haruyq.createmediasystems.audio.NoteBlockLocks;
import org.haruyq.createmediasystems.audio.NoteBlockSounds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

@Mixin(NoteBlock.class)
abstract class NoteBlockMixin extends Block {
    private NoteBlockMixin(Properties properties) {
        super(properties);
    }

    @ModifyExpressionValue(method = "triggerEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/properties/NoteBlockInstrument;getSoundEvent()Lnet/minecraft/core/Holder;"))
    private Holder<SoundEvent> create_media_systems$copperInstrument(Holder<SoundEvent> original, BlockState state,
                                                                   Level level, BlockPos pos, int id, int param) {
        return NoteBlockSounds.sound(original, level.getBlockState(pos.below()));
    }

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void create_media_systems$lockedTuning(BlockState state, Level level, BlockPos pos, Player player,
                                                   BlockHitResult hit, CallbackInfoReturnable<InteractionResult> result) {
        InteractionResult locked = NoteBlockLocks.use(state, level, pos, player);
        if (locked != null) result.setReturnValue(locked);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) NoteBlockLocks.removed(level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
