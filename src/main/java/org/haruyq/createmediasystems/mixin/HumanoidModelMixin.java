package org.haruyq.createmediasystems.mixin;

import org.haruyq.createmediasystems.client.ArmPoses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;

/** Poses a player's arms for what they hold (see ArmPoses), over vanilla's pose. */
@Mixin(HumanoidModel.class)
abstract class HumanoidModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void create_media_systems$armPoses(LivingEntity entity, float walk, float amount, float age, float yaw,
                                               float pitch, CallbackInfo ci) {
        ArmPoses.pose((HumanoidModel<?>) (Object) this, entity, yaw, pitch);
    }
}
