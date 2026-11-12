package org.haruyq.createmediasystems.megaphone.client;

import org.haruyq.createmediasystems.client.DevicePanel;
import org.haruyq.createmediasystems.client.PlaybackSeekBar;
import org.haruyq.createmediasystems.stream.PortablePlaylist;
import org.haruyq.createmediasystems.power.ChargeableItem;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.megaphone.*;

public final class MegaphoneScreen extends AbstractContainerScreen<MegaphoneMenu> {
    private Button play,drive,previous,next;
    private PlaybackSeekBar seek;
    public MegaphoneScreen(MegaphoneMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=300;imageHeight=226;}
    private static Component tr(String key){return Component.translatable("megaphone."+key);}
    private static final DevicePanel.Palette PALETTE=new DevicePanel.Palette(0xFF29323D,0xFF414B58,0xFF586473,0xFFFF794B,0xFFF2E9DE);
    private void action(MegaphoneAction.Kind kind,int value){PacketDistributor.sendToServer(new MegaphoneAction(menu.containerId,kind,value));}
    private Button button(int x,int y,int w,Component text,Runnable run){return addRenderableWidget(DevicePanel.button(leftPos+x,topPos+y,w,text,PALETTE,run));}
    @Override protected void init(){super.init();
        previous=button(116,42,30,Component.literal("|<"),()->action(MegaphoneAction.Kind.PREVIOUS,0));
        previous.setTooltip(Tooltip.create(Component.translatable("portable.previous")));
        play=button(150,42,100,Component.empty(),()->action(MegaphoneAction.Kind.PLAY_PAUSE,0));
        next=button(254,42,30,Component.literal(">|"),()->action(MegaphoneAction.Kind.NEXT,0));
        next.setTooltip(Tooltip.create(Component.translatable("portable.next")));
        button(116,66,30,Component.literal("-10s"),()->action(MegaphoneAction.Kind.SEEK_BY,-10));
        seek=addRenderableWidget(new PlaybackSeekBar(leftPos+150,topPos+66,100,value->action(MegaphoneAction.Kind.SEEK,value)));
        button(254,66,30,Component.literal("+10s"),()->action(MegaphoneAction.Kind.SEEK_BY,10));
        button(116,90,24,Component.literal("−"),()->action(MegaphoneAction.Kind.VOLUME,MegaphoneData.volume(menu.horn())-2));
        button(260,90,24,Component.literal("+"),()->action(MegaphoneAction.Kind.VOLUME,MegaphoneData.volume(menu.horn())+2));
        drive=button(116,114,168,Component.empty(),()->action(MegaphoneAction.Kind.DRIVE,(MegaphoneData.drive(menu.horn())+1)%3));
    }
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        var item=menu.horn();
        int tracks=PortablePlaylist.size(MegaphoneData.disc(item,minecraft.level));
        int track=MegaphoneData.track(item);
        play.setMessage(tr(MegaphoneData.playing(item)?"pause":"play"));
        play.active=MegaphoneData.playing(item) || tracks>0 && ChargeableItem.energy(MegaphoneData.battery(item,minecraft.level))>0;
        previous.active=track>0;
        next.active=track+1<tracks;
        seek.show(MegaphoneData.seconds(item,minecraft.level),MegaphoneData.duration(item));
        drive.setMessage(tr("drive_"+MegaphoneData.drive(item)));
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
        int tracks=PortablePlaylist.size(MegaphoneData.disc(item,minecraft.level));
        if(tracks>0)g.drawString(font,Component.translatable("portable.track",MegaphoneData.track(item)+1,tracks),
                x+12,y+80,0xFFE4E9ED,false);
        g.drawCenteredString(font,Component.translatable("megaphone.volume",MegaphoneData.volume(item)),x+200,y+96,0xFFEDE2CF);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){}
    @Override public boolean mouseReleased(double x,double y,int button){
        if(button==0)seek.release();
        return super.mouseReleased(x,y,button);
    }
}
