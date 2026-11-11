package org.haruyq.createmediasystems.megaphone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import org.haruyq.createmediasystems.partybox.PartyBoxBatteryItem;

public final class MegaphoneMenu extends AbstractContainerMenu {
    private final Inventory inventory;private final int index;private final ItemStack original;
    private final SimpleContainer contents=new SimpleContainer(2);private boolean updating;
    public MegaphoneMenu(int id,Inventory inventory,RegistryFriendlyByteBuf data){this(id,inventory,data.readVarInt());}
    public MegaphoneMenu(int id,Inventory inventory,int index) {
        super(MegaphoneFeature.MEGAPHONE_MENU.get(),id);this.inventory=inventory;this.index=index;original=inventory.getItem(index);
        var level=inventory.player.level();contents.setItem(0,MegaphoneData.disc(original,level));contents.setItem(1,MegaphoneData.battery(original,level));
        contents.addListener(c->{if(!updating && !level.isClientSide && stillValid(inventory.player)){
            MegaphoneData.contents(original,level,c.getItem(0),c.getItem(1));inventory.setChanged();}});
        addSlot(new Slot(contents,0,28,60){@Override public boolean mayPlace(ItemStack item){return MediaItem.fits(item, DiscKind.Slot.PORTABLE);}@Override public int getMaxStackSize(){return 1;}});
        addSlot(new Slot(contents,1,28,108){@Override public boolean mayPlace(ItemStack item){return item.getItem() instanceof PartyBoxBatteryItem;}@Override public int getMaxStackSize(){return 1;}});
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)playerSlot(col+row*9+9,116+col*18,142+row*18);
        for(int col=0;col<9;col++)playerSlot(col,116+col*18,200);
    }
    private void playerSlot(int slot,int x,int y){addSlot(new Slot(inventory,slot,x,y){@Override public boolean mayPickup(Player p){return slot!=index;}@Override public boolean mayPlace(ItemStack s){return slot!=index;}});}
    public ItemStack horn(){return inventory.getItem(index);}
    @Override public boolean stillValid(Player player){return index>=0 && index<inventory.getContainerSize() && inventory.getItem(index)==original && original.getItem() instanceof MegaphoneItem;}
    @Override public void clicked(int slot,int button,ClickType type,Player player) {
        if(type==ClickType.SWAP && (button==index || button==40 && index==40))return;
        if(slot>=2 && slot<slots.size() && slots.get(slot).getContainerSlot()==index)return;
        if(!player.level().isClientSide)refreshBattery();super.clicked(slot,button,type,player);
    }
    private void refreshBattery(){MegaphoneData.tick(original,inventory.player.level());updating=true;contents.setItem(1,MegaphoneData.battery(original,inventory.player.level()));updating=false;}
    @Override public void broadcastChanges(){if(!inventory.player.level().isClientSide && stillValid(inventory.player))refreshBattery();super.broadcastChanges();}
    @Override public ItemStack quickMoveStack(Player player,int slot) {
        if(slot<0 || slot>=slots.size())return ItemStack.EMPTY;var s=slots.get(slot);if(!s.hasItem() || !s.mayPickup(player))return ItemStack.EMPTY;
        var stack=s.getItem();var before=stack.copy();
        boolean moved=slot<2?moveItemStackTo(stack,2,slots.size(),true):stack.getItem() instanceof PartyBoxBatteryItem?moveItemStackTo(stack,1,2,false):MediaItem.fits(stack, DiscKind.Slot.PORTABLE)&&moveItemStackTo(stack,0,1,false);
        if(!moved)return ItemStack.EMPTY;if(stack.isEmpty())s.setByPlayer(ItemStack.EMPTY);else s.setChanged();s.onTake(player,stack);return before;
    }
}
