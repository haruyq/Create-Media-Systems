package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.launcher.DiscCellItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;

public final class DiscCellChargerRenderer implements BlockEntityRenderer<DiscCellCharger> {
    public DiscCellChargerRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(DiscCellCharger charger, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (charger.cell().isEmpty()) return;
        CellGlowRenderer.track(charger);
        pose.pushPose();
        // Rest the actual bottom on the socket, including each item's FIXED transform.
        pose.translate(.5, charger.cell().getItem() instanceof DiscCellItem ? .55125 : .61125, .5);
        double time = charger.getLevel() == null ? 0 : charger.getLevel().getGameTime() + (double)partial;
        pose.mulPose(Axis.YP.rotationDegrees((float)(time * Math.min(1.5f, Math.abs(charger.getSpeed()) / 64f)%360)));
        pose.scale(.6f, .6f, .6f);
        Minecraft.getInstance().getItemRenderer().renderStatic(charger.cell(), ItemDisplayContext.FIXED, light, overlay,
                pose, buffers, charger.getLevel(), 0);
        pose.popPose();
    }
}
