package org.haruyq.createmediasystems.partybox.client;

import org.haruyq.createmediasystems.client.DevicePanel;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.partybox.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PartyBoxScreen extends AbstractContainerScreen<PartyBoxMenu> {
    private Button play,bass,lights,pull;
    public PartyBoxScreen(PartyBoxMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=300;imageHeight=240;}
    private static final DevicePanel.Palette PALETTE=new DevicePanel.Palette(0xFF2A3039,0xFF414852,0xFF5C6572,0xFFFF5A2E,0xFFE8EDF3);
    private void action(PartyBoxAction.Kind kind,int value){if(menu.box!=null)PacketDistributor.sendToServer(new PartyBoxAction(menu.box.getId(),kind,value));}
    private Button button(int x,int y,int w,Component name,Runnable action){return addRenderableWidget(DevicePanel.button(leftPos+x,topPos+y,w,name,PALETTE,action));}
    @Override protected void init(){
        super.init();
        play=button(116,48,168,Component.empty(),()->action(PartyBoxAction.Kind.PLAY_PAUSE,0));
        button(116,76,23,Component.literal("−"),()->action(PartyBoxAction.Kind.VOLUME,menu.box.volume()-2));
        button(261,76,23,Component.literal("+"),()->action(PartyBoxAction.Kind.VOLUME,menu.box.volume()+2));
        bass=button(116,102,168,Component.empty(),()->action(PartyBoxAction.Kind.BASS,(menu.box.bass()+1)%3));
        lights=button(116,128,168,Component.empty(),()->action(PartyBoxAction.Kind.LIGHTS,menu.box.lights()+1));
        pull=button(12,145,91,tr("pull"),()->action(PartyBoxAction.Kind.PULL,0));
        button(12,171,91,tr("pack"),()->action(PartyBoxAction.Kind.PACK,0));
    }
    private static Component tr(String key){return Component.translatable("partybox."+key);}
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        var box=menu.box;
        if(box!=null){
            play.setMessage(tr(box.playing()?"pause":"play"));
            bass.setMessage(Component.translatable("partybox.bass",box.bass()==0?"OFF":box.bass()==1?"DEEP":"PUNCHY"));
            lights.setMessage(Component.translatable("partybox.lights",Component.translatable("partybox.light_"+box.lights())));
            pull.setMessage(tr(box.puller().isPresent()?"release":"pull"));
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
        if(menu.box!=null){var box=menu.box;
            g.drawCenteredString(font,Component.translatable("partybox.volume",box.volume()),x+200,y+82,0xFFE4E9ED);
            float charge=(float)box.energy()/BatteryItem.CAPACITY;
            DevicePanel.gauge(g,x+12,y+134,x+99,y+138,charge,0xFF3A414A,0xFF65DCA0);
            g.drawString(font,DevicePanel.percent(charge),x+49,y+114,0xFFE4E9ED,false);
            g.drawString(font,DevicePanel.clock(box.positionTicks()),x+65,y+62,0xFFE4E9ED,false);
        }
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){}
}
