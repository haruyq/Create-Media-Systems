package org.haruyq.createmediasystems.items.partybox.stage320.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;

public final class PartyBoxItemRenderer extends BlockEntityWithoutLevelRenderer {
    public PartyBoxItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(.5,.16,.5);
        PartyBoxMesh.draw(pose,buffers,light,0,0,0,0,0,false);
        pose.popPose();
    }
}
