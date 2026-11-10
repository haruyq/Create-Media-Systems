package org.haruyq.createmediasystems.launcher.client;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.haruyq.createmediasystems.megaphone.client.MegaphoneMesh;

final class MegaphoneModelAuditScreen extends Screen {
    private final int angle;
    MegaphoneModelAuditScreen(int angle){super(Component.literal("Horn seam audit"));this.angle=angle;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int x,int y,float partial) {
        float gui=(float)minecraft.getWindow().getGuiScale();int w=minecraft.getWindow().getWidth(),h=minecraft.getWindow().getHeight();
        g.pose().pushPose();g.pose().scale(1/gui,1/gui,1);g.fill(0,0,w,h,0xFF3F5269);
        g.drawString(font,"CMS 1.6.1 / HORN LIP + LINING / "+angle+" DEG",24,24,0xFFEAF1EF,false);
        g.pose().translate(w*.5,h*.43,150);g.pose().scale(1400,-1400,1400);
        g.pose().mulPose(Axis.YP.rotationDegrees(180+angle));
        MegaphoneMesh.draw(g.pose(),g.bufferSource(),0xF000F0,false,true);g.flush();g.pose().popPose();
    }
}
