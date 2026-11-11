package org.haruyq.createmediasystems.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.launcher.LauncherMagazine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class LauncherArmMixin {
    @Shadow public ModelPart head;
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("TAIL"))
    private void cms$shoulder(LivingEntity entity,float walk,float amount,float age,float yaw,float pitch,CallbackInfo ci) {
        if(!(entity instanceof Player p) || !p.getMainHandItem().is(LauncherFeature.DISC_LAUNCHER.get()))return;
        boolean right=p.getMainArm()==HumanoidArm.RIGHT,reloading=LauncherMagazine.reloading(p.getMainHandItem());
        ModelPart trigger=right?rightArm:leftArm,support=right?leftArm:rightArm;
        trigger.xRot=-1.42f+head.xRot;trigger.yRot=head.yRot+(right?-.12f:.12f);trigger.zRot=0;
        support.xRot=(reloading?-.85f:-1.40f)+head.xRot;support.yRot=head.yRot+(right?.42f:-.42f);support.zRot=right?-.10f:.10f;
    }
}
