package org.haruyq.createmediasystems.items.megaphone.megaphone30;

import org.haruyq.createmediasystems.FittingSlot;
import org.haruyq.createmediasystems.HeldItemMenu;
import org.haruyq.createmediasystems.items.power.BatteryItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.haruyq.createmediasystems.stream.PortablePlaylist;

public final class MegaphoneMenu extends HeldItemMenu {
    private final SimpleContainer contents=new SimpleContainer(2);private boolean updating;
    public MegaphoneMenu(int id,Inventory inventory,RegistryFriendlyByteBuf data){this(id,inventory,data.readVarInt());}
    public MegaphoneMenu(int id,Inventory inventory,int index) {
        super(MegaphoneFeature.MEGAPHONE_MENU.get(),id,inventory,index,MegaphoneItem.class);
        var level=inventory.player.level();contents.setItem(0,MegaphoneData.disc(item,level));contents.setItem(1,MegaphoneData.battery(item,level));
        contents.addListener(c->{if(!updating && !level.isClientSide && stillValid(inventory.player)){
            MegaphoneData.contents(item,level,c.getItem(0),c.getItem(1));inventory.setChanged();}});
        addSlot(new FittingSlot(contents,0,28,60,PortablePlaylist::fits));
        addSlot(new FittingSlot(contents,1,28,108,BatteryItem::fits));
        addPlayerSlots(116,142);
    }
    public ItemStack horn(){return inventory.getItem(held);}
    @Override public void clicked(int slot,int button,ClickType type,Player player) {
        if(!player.level().isClientSide)refreshBattery();super.clicked(slot,button,type,player);
    }
    private void refreshBattery(){MegaphoneData.tick(item,inventory.player.level());updating=true;contents.setItem(1,MegaphoneData.battery(item,inventory.player.level()));updating=false;}
    @Override public void broadcastChanges(){if(!inventory.player.level().isClientSide && stillValid(inventory.player))refreshBattery();super.broadcastChanges();}
    @Override public ItemStack quickMoveStack(Player player,int slot) {
        if(slot<0 || slot>=slots.size())return ItemStack.EMPTY;var s=slots.get(slot);if(!s.hasItem() || !s.mayPickup(player))return ItemStack.EMPTY;
        var stack=s.getItem();var before=stack.copy();
        boolean moved=slot<2?moveItemStackTo(stack,2,slots.size(),true):moveItemStackTo(stack,0,2,false);
        if(!moved)return ItemStack.EMPTY;if(stack.isEmpty())s.setByPlayer(ItemStack.EMPTY);else s.setChanged();s.onTake(player,stack);return before;
    }
}
