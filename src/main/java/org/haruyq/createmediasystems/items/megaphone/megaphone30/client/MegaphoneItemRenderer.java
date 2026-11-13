package org.haruyq.createmediasystems.items.megaphone.megaphone30.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.*;
import org.haruyq.createmediasystems.items.megaphone.megaphone30.*;

public final class MegaphoneItemRenderer extends BlockEntityWithoutLevelRenderer {
    public MegaphoneItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    @Override public void renderByItem(ItemStack item,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(.5,.52,.5);pose.scale(1.5f,1.5f,1.5f);
        MegaphoneMesh.draw(pose,buffers,light,item.getItem() instanceof MegaphoneItem horn && horn.black,MegaphoneData.playing(item));pose.popPose();
    }
}
