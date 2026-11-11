package org.haruyq.createmediasystems.partybox.client;

import org.haruyq.createmediasystems.client.DevicePanel;
import org.haruyq.createmediasystems.client.PlaybackSeekBar;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.partybox.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PartyBoxScreen extends AbstractContainerScreen<PartyBoxMenu> {
    private Button play,bass,lights,pull,previous,next;
    private PlaybackSeekBar seek;
    public PartyBoxScreen(PartyBoxMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=300;imageHeight=240;}
    private static final DevicePanel.Palette PALETTE=new DevicePanel.Palette(0xFF2A3039,0xFF414852,0xFF5C6572,0xFFFF5A2E,0xFFE8EDF3);
    private void action(PartyBoxAction.Kind kind,int value){
        if(menu.box()!=null)PacketDistributor.sendToServer(new PartyBoxAction(menu.box().getId(),kind,value));
    }
    private Button button(int x,int y,int w,Component name,Runnable action){return addRenderableWidget(DevicePanel.button(leftPos+x,topPos+y,w,name,PALETTE,action));}
    @Override protected void init(){
        super.init();
        previous=button(116,42,30,Component.literal("|<"),()->action(PartyBoxAction.Kind.PREVIOUS,0));
        previous.setTooltip(Tooltip.create(Component.translatable("portable.previous")));
        play=button(150,42,100,Component.empty(),()->action(PartyBoxAction.Kind.PLAY_PAUSE,0));
        next=button(254,42,30,Component.literal(">|"),()->action(PartyBoxAction.Kind.NEXT,0));
        next.setTooltip(Tooltip.create(Component.translatable("portable.next")));
        button(116,66,30,Component.literal("-10s"),()->action(PartyBoxAction.Kind.SEEK_BY,-10));
        seek=addRenderableWidget(new PlaybackSeekBar(leftPos+150,topPos+66,100,value->action(PartyBoxAction.Kind.SEEK,value)));
        button(254,66,30,Component.literal("+10s"),()->action(PartyBoxAction.Kind.SEEK_BY,10));
        button(116,91,23,Component.literal("−"),()->{var box=menu.box();if(box!=null)action(PartyBoxAction.Kind.VOLUME,box.volume()-2);});
        button(261,91,23,Component.literal("+"),()->{var box=menu.box();if(box!=null)action(PartyBoxAction.Kind.VOLUME,box.volume()+2);});
        bass=button(116,117,82,Component.empty(),()->{var box=menu.box();if(box!=null)action(PartyBoxAction.Kind.BASS,(box.bass()+1)%3);});
        lights=button(202,117,82,Component.empty(),()->{var box=menu.box();if(box!=null)action(PartyBoxAction.Kind.LIGHTS,box.lights()+1);});
        pull=button(12,145,91,tr("pull"),()->action(PartyBoxAction.Kind.PULL,0));
        button(12,171,91,tr("pack"),()->action(PartyBoxAction.Kind.PACK,0));
    }
    private static Component tr(String key){return Component.translatable("partybox."+key);}
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        var box=menu.box();
        if(box!=null){
            play.setMessage(tr(box.playing()?"pause":"play"));
            play.active=box.playing() || box.energy()>0 && box.trackCount()>0;
            previous.active=box.track()>0;
            next.active=box.track()+1<box.trackCount();
            seek.show(box.seconds(),box.duration());
            bass.setMessage(Component.translatable("partybox.bass",box.bass()==0?"OFF":box.bass()==1?"DEEP":"PUNCHY"));
            lights.setMessage(Component.translatable("partybox.lights",Component.translatable("partybox.light_"+box.lights())));
            pull.setMessage(tr(box.puller().isPresent()?"release":"pull"));
        }else{
            play.active=previous.active=next.active=false;
            play.setMessage(Component.translatable("portable.loading"));
            seek.show(0,-1);
        }
        super.render(g,x,y,partial);renderTooltip(g,x,y);
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mouseX,int mouseY){
        int x=leftPos,y=topPos;
        g.fill(x-1,y-1,x+imageWidth+1,y+imageHeight+1,0xFF393D44);g.fill(x,y,x+imageWidth,y+imageHeight,0xFF111319);
        g.fill(x,y,x+imageWidth,y+31,0xFF24272E);g.fill(x,y,x+4,y+31,0xFFFF592C);
        g.drawString(font,"SBL",x+13,y+10,0xFFFF592C,false);g.drawString(font,"PARTYBOX  /  STAGE 320",x+47,y+10,0xFFF0F2F5,false);
        for(var slot:menu.slots){int sx=x+slot.x,sy=y+slot.y;g.fill(sx-1,sy-1,sx+17,sy+17,0xFF42474F);g.fill(sx,sy,sx+16,sy+16,0xFF22262D);}
        g.drawString(font,tr("source"),x+12,y+42,0xFFAEB8C4,false);
        g.drawString(font,tr("battery"),x+12,y+93,0xFFAEB8C4,false);
        var box=menu.box();
        if(box!=null){
            g.drawCenteredString(font,Component.translatable("partybox.volume",box.volume()),x+200,y+97,0xFFE4E9ED);
            float charge=(float)box.energy()/BatteryItem.CAPACITY;
            DevicePanel.gauge(g,x+12,y+134,x+99,y+138,charge,0xFF3A414A,0xFF65DCA0);
            g.drawString(font,DevicePanel.percent(charge),x+49,y+114,0xFFE4E9ED,false);
            if(box.trackCount()>0)g.drawString(font,Component.translatable("portable.track",box.track()+1,box.trackCount()),
                    x+12,y+78,0xFFE4E9ED,false);
        }
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){}
    @Override public boolean mouseReleased(double x,double y,int button){
        if(button==0)seek.release();
        return super.mouseReleased(x,y,button);
    }
}
