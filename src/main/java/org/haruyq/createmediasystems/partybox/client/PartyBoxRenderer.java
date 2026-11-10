package org.haruyq.createmediasystems.partybox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.haruyq.createmediasystems.partybox.PartyBoxEntity;
import org.haruyq.createmediasystems.launcher.client.LauncherRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class PartyBoxRenderer extends EntityRenderer<PartyBoxEntity> {
    public PartyBoxRenderer(EntityRendererProvider.Context context) { super(context);shadowRadius=.22f; }
    @Override public ResourceLocation getTextureLocation(PartyBoxEntity box) { return LauncherRenderer.MATERIALS; }
    @Override public void render(PartyBoxEntity box,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partial,box.yRotO,box.getYRot())));
        PartyBoxMesh.draw(pose,buffers,light,Mth.lerp(partial,box.previousLean,box.lean)/19,
                Mth.lerp(partial,box.previousWheelTravel,box.wheelTravel),
                (box.level().getGameTime()+(double)partial)/20.0,box.lights(),PartyBoxClient.beat(box),box.playing()&&box.energy()>0);
        pose.popPose();super.render(box,yaw,partial,pose,buffers,light);
    }
}
