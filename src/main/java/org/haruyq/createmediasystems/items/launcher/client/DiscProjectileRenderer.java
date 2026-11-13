package org.haruyq.createmediasystems.items.launcher.client;

import org.haruyq.createmediasystems.client.mesh.Tint;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.launcher.DiscProjectile;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class DiscProjectileRenderer extends EntityRenderer<DiscProjectile> {
    public DiscProjectileRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void render(DiscProjectile entity, float yaw, float partial, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        var target=entity.attached()?entity.attachmentTarget():null;
        if(entity.attached() && target==null)return;
        var mc=Minecraft.getInstance();
        if(target==mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson())return;
        pose.pushPose();
        if(target!=null) {
            var point=entity.attachmentPosition(partial);
            pose.translate(point.x-Mth.lerp(partial,entity.xOld,entity.getX()),point.y-Mth.lerp(partial,entity.yOld,entity.getY()),
                    point.z-Mth.lerp(partial,entity.zOld,entity.getZ()));
            var direction=entity.attachmentDirection(partial);
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0,0,1),new Vector3f((float)direction.x,(float)direction.y,(float)direction.z)));
            // The blade's plane contains the incoming trajectory, leaving half the disc protruding from the skin.
            pose.mulPose(Axis.ZP.rotationDegrees(90));
        } else {
            pose.mulPose(Axis.YP.rotationDegrees(-(yaw + 90f)));
            pose.mulPose(Axis.XP.rotationDegrees(90f));
        }
        pose.mulPose(Axis.YP.rotationDegrees(entity.embedded()?Math.floorMod(entity.getUUID().hashCode(),360):(entity.tickCount+partial)*46f));
        pose.scale(.09f,.09f,.09f);
        LauncherRenderer.disc(pose, buffers.getBuffer(Mesh.solid()),
                0,0,0,3.6f,packedLight,OverlayTexture.NO_OVERLAY);
        LauncherRenderer.discGlowRing(pose, new Tint(buffers.getBuffer(Mesh.emissive()),
                entity.cellTier().color, 90), 0xF000F0, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(entity,yaw,partial,pose,buffers,packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(DiscProjectile entity) { return Mesh.ATLAS; }
}
