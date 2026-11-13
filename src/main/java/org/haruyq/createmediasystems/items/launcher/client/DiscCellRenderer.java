package org.haruyq.createmediasystems.items.launcher.client;

import org.haruyq.createmediasystems.items.power.ChargeableItem;
import org.haruyq.createmediasystems.client.mesh.CellLight;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.items.launcher.DiscCellItem;
import org.haruyq.createmediasystems.items.launcher.CellLighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public final class DiscCellRenderer extends BlockEntityWithoutLevelRenderer {
    public DiscCellRenderer() { super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels()); }
    @Override public void renderByItem(ItemStack stack, ItemDisplayContext display, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!(stack.getItem() instanceof DiscCellItem item)) return;
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.scale(1 / 12f, 1 / 12f, 1 / 12f);
        var luminous = buffers.getBuffer(Mesh.solid());
        float charge = ChargeableItem.charge(stack);
        var mesh = new CellLight(pose.last().pose(), 0, 0, 0, 12, item.tier.color, CellLighting.strength(charge) * 1.8f).wrap(luminous);
        LauncherRenderer.cell(pose, mesh, luminous, 0, 0, 0, item.tier.color, charge, light, overlay);
        pose.popPose();
    }
}
