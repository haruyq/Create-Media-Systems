package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.launcher.DiscCellTier;
import org.haruyq.createmediasystems.power.PowerFeature;
import org.haruyq.createmediasystems.power.ChargeableItem;
import java.util.*;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.megaphone.MegaphoneFeature;
import org.haruyq.createmediasystems.partybox.PartyBoxFeature;
import org.haruyq.createmediasystems.launcher.*;

/** Fixed-camera native frames for checking winding, opacity, atlas bleed and unintended flicker. */
final class LauncherModelAuditScreen extends Screen {
    private final List<ItemStack> items=new ArrayList<>();private final int angle;
    LauncherModelAuditScreen(int angle){super(Component.literal("CMS model audit"));this.angle=angle;
        var gun=new ItemStack(LauncherFeature.DISC_LAUNCHER.get());var cell=new ItemStack(LauncherFeature.cell(DiscCellTier.NETHERITE));
        DiscCellItem.setMaster(cell,new ItemStack(Items.MUSIC_DISC_CAT));ChargeableItem.setEnergy(cell,DiscCellTier.NETHERITE.capacity());LauncherMagazine.setCell(gun,cell);items.add(gun);
        for(var tier:DiscCellTier.values()) {var c=new ItemStack(LauncherFeature.cell(tier));ChargeableItem.setEnergy(c,tier.capacity());items.add(c);}
        items.add(new ItemStack(PartyBoxFeature.PARTYBOX.get()));items.add(new ItemStack(PowerFeature.BATTERY.get()));
        items.add(new ItemStack(MegaphoneFeature.MEGAPHONE_WHITE.get()));items.add(new ItemStack(MegaphoneFeature.MEGAPHONE_BLACK.get()));items.add(new ItemStack(LauncherFeature.DISC_CELL_CHARGER.get()));
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        var window=minecraft.getWindow();float scale=(float)window.getGuiScale();int width=window.getWidth(),height=window.getHeight();
        g.pose().pushPose();g.pose().scale(1/scale,1/scale,1);g.fill(0,0,width,height,0xFF101722);
        g.drawString(font,"CMS 1.6.0 / NATIVE MODEL AUDIT / "+angle+" deg",24,18,0xFFE7F2EF,false);
        int w=(width-48)/4,h=(height-65)/3;
        for(int i=0;i<items.size();i++){
            int x=24+(i%4)*w,y=42+(i/4)*h;g.fill(x+3,y+3,x+w-6,y+h-6,0xFF1D2937);
            var item=items.get(i);g.drawString(font,item.getHoverName(),x+12,y+12,0xFFD3DFE5,false);
            g.pose().pushPose();g.pose().translate(x+w*.5f,y+h*.53f,100);g.pose().mulPose(Axis.YP.rotationDegrees(angle));g.pose().scale(6,6,6);g.pose().translate(-8,-8,0);g.renderItem(item,0,0);g.pose().popPose();
        }
        g.pose().popPose();
    }
}
