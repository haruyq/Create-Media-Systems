package org.haruyq.createmediasystems.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class PartyBoxArmMixin {
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("TAIL"))
    private void cms$pulling(LivingEntity entity,float walk,float amount,float age,float yaw,float pitch,CallbackInfo ci){
        if(entity instanceof Player player && org.haruyq.createmediasystems.partybox.client.PartyBoxClient.pulling(player)){
            var arm=player.getMainArm()==HumanoidArm.RIGHT?rightArm:leftArm;
            arm.xRot=.78f;arm.yRot=player.getMainArm()==HumanoidArm.RIGHT?-.22f:.22f;
            arm.zRot=player.getMainArm()==HumanoidArm.RIGHT?-.28f:.28f;
        }
    }
}
