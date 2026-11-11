package org.haruyq.createmediasystems.megaphone.client;

import org.haruyq.createmediasystems.client.DevicePanel;
import org.haruyq.createmediasystems.power.ChargeableItem;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.megaphone.*;

public final class MegaphoneScreen extends AbstractContainerScreen<MegaphoneMenu> {
    private Button play,drive;
    public MegaphoneScreen(MegaphoneMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=300;imageHeight=226;}
    private static Component tr(String key){return Component.translatable("megaphone."+key);}
    private static final DevicePanel.Palette PALETTE=new DevicePanel.Palette(0xFF29323D,0xFF414B58,0xFF586473,0xFFFF794B,0xFFF2E9DE);
    private void action(MegaphoneAction.Kind kind,int value){PacketDistributor.sendToServer(new MegaphoneAction(menu.containerId,kind,value));}
    private Button button(int x,int y,int w,Component text,Runnable run){return addRenderableWidget(DevicePanel.button(leftPos+x,topPos+y,w,text,PALETTE,run));}
    @Override protected void init(){super.init();
        play=button(116,49,168,Component.empty(),()->action(MegaphoneAction.Kind.PLAY_PAUSE,0));
        button(116,80,24,Component.literal("−"),()->action(MegaphoneAction.Kind.VOLUME,MegaphoneData.volume(menu.horn())-2));
        button(260,80,24,Component.literal("+"),()->action(MegaphoneAction.Kind.VOLUME,MegaphoneData.volume(menu.horn())+2));
        drive=button(116,109,168,Component.empty(),()->action(MegaphoneAction.Kind.DRIVE,(MegaphoneData.drive(menu.horn())+1)%3));
    }
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        play.setMessage(tr(MegaphoneData.playing(menu.horn())?"pause":"play"));drive.setMessage(tr("drive_"+MegaphoneData.drive(menu.horn())));
        super.render(g,x,y,partial);renderTooltip(g,x,y);
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my){
        int x=leftPos,y=topPos;var item=menu.horn();
        g.fill(x-1,y-1,x+301,y+227,0xFF444A51);g.fill(x,y,x+300,y+226,0xFF11151A);g.fill(x,y,x+300,y+31,0xFF262C33);g.fill(x,y,x+4,y+31,0xFFFF6034);
        g.drawString(font,"SBL",x+13,y+10,0xFFFF6034,false);g.drawString(font,"MEGAPHONE  /  30 W",x+46,y+10,0xFFF0F2F4,false);
        for(var slot:menu.slots){int sx=x+slot.x,sy=y+slot.y;g.fill(sx-1,sy-1,sx+17,sy+17,0xFF505861);g.fill(sx,sy,sx+16,sy+16,0xFF242A31);}
        g.drawString(font,tr("source"),x+12,y+44,0xFFC3CBD2,false);g.drawString(font,tr("battery"),x+12,y+92,0xFFC3CBD2,false);
        float charge=ChargeableItem.charge(MegaphoneData.battery(item,minecraft.level));
        DevicePanel.gauge(g,x+12,y+133,x+99,y+136,charge,0xFF343C45,0xFFFF8854);
        g.drawString(font,DevicePanel.percent(charge),x+54,y+113,0xFFE4E9ED,false);
        g.drawString(font,DevicePanel.clock(MegaphoneData.position(item,minecraft.level)),x+56,y+65,0xFFE4E9ED,false);
        g.drawCenteredString(font,Component.translatable("megaphone.volume",MegaphoneData.volume(item)),x+200,y+86,0xFFEDE2CF);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){}
}
