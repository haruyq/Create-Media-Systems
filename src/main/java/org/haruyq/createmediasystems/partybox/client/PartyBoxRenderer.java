package org.haruyq.createmediasystems.partybox.client;

import org.haruyq.createmediasystems.client.PointLights;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.haruyq.createmediasystems.partybox.PartyBoxEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class PartyBoxRenderer extends EntityRenderer<PartyBoxEntity> {
    static final PointLights.Source LIGHTS = (view, lights) -> {
        double time = (view.level().getGameTime() + (double) view.partial()) / 20.0;
        for (var entity : view.entities()) {
            if (entity instanceof PartyBoxEntity box && box.playing() && box.energy() > 0 && box.lights() != 0) {
                lights.accept(new PointLights.Light(box.soundPosition().add(Vec3.directionFromRotation(0, box.getYRot() + 180).scale(.25)),
                        PartyBoxMesh.lightColor(time, box.lights()), .6f));
            }
        }
    };

    public PartyBoxRenderer(EntityRendererProvider.Context context) { super(context);shadowRadius=.22f; }
    @Override public ResourceLocation getTextureLocation(PartyBoxEntity box) { return Mesh.ATLAS; }
    @Override public void render(PartyBoxEntity box,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partial,box.yRotO,box.getYRot())));
        PartyBoxMesh.draw(pose,buffers,light,Mth.lerp(partial,box.previousLean,box.lean)/19,
                Mth.lerp(partial,box.previousWheelTravel,box.wheelTravel),
                (box.level().getGameTime()+(double)partial)/20.0,box.lights(),PartyBoxClient.beat(box),box.playing()&&box.energy()>0);
        pose.popPose();super.render(box,yaw,partial,pose,buffers,light);
    }
}
