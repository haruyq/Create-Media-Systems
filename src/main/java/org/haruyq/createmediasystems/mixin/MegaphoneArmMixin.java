package org.haruyq.createmediasystems.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import org.haruyq.createmediasystems.megaphone.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class MegaphoneArmMixin {
    @Shadow public ModelPart rightArm;@Shadow public ModelPart leftArm;
    @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("TAIL"))
    private void cms$horn(LivingEntity entity,float walk,float amount,float age,float yaw,float pitch,CallbackInfo ci){
        if(entity instanceof Player p) {
            for(var hand:net.minecraft.world.InteractionHand.values()) {
                var item=p.getItemInHand(hand);if(!(item.getItem() instanceof MegaphoneItem) || !MegaphoneData.playing(item))continue;
                boolean right=(p.getMainArm()==HumanoidArm.RIGHT)==(hand==net.minecraft.world.InteractionHand.MAIN_HAND);
                var arm=right?rightArm:leftArm;arm.xRot=(float)(-Math.PI/2+Math.toRadians(pitch)*.8);arm.yRot=(float)Math.toRadians(yaw)+(right?-.12f:.12f);arm.zRot=0;
            }
        }
    }
}
