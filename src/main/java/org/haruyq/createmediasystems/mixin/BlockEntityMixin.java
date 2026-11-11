package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.stream.BlockIdentity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = {"setLevel", "loadWithComponents"}, at = @At("TAIL"))
    private void create_media_systems$followSource(CallbackInfo ci) {
        BlockIdentity.loaded((BlockEntity) (Object) this);
    }
}
