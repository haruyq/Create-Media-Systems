package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.FittingSlot;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class PartyBoxMenu extends AbstractContainerMenu {
    public final PartyBoxEntity box;
    public PartyBoxMenu(int id, Inventory player, RegistryFriendlyByteBuf data) {
        this(id, player, player.player.level().getEntity(data.readVarInt()) instanceof PartyBoxEntity box ? box : null);
    }
    public PartyBoxMenu(int id, Inventory player, PartyBoxEntity box) {
        super(PartyBoxFeature.PARTYBOX_MENU.get(), id); this.box=box;
        var storage = box == null ? new net.minecraft.world.SimpleContainer(2) : box.inventory;
        addSlot(new FittingSlot(storage, 0, 25, 58, item -> MediaItem.fits(item, DiscKind.Slot.PORTABLE)));
        addSlot(new FittingSlot(storage, 1, 25, 109, BatteryItem::fits));
        for(int row=0;row<3;row++) for(int col=0;col<9;col++) addSlot(new Slot(player,9+row*9+col,116+col*18,152+row*18));
        for(int col=0;col<9;col++) addSlot(new Slot(player,col,116+col*18,210));
    }
    @Override public boolean stillValid(Player player) { return box != null && !box.isRemoved() && box.distanceToSqr(player) < 64; }
    @Override public void clicked(int slot,int button,net.minecraft.world.inventory.ClickType type,Player player){
        if(box!=null)box.settlePower();super.clicked(slot,button,type,player);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if(box!=null)box.settlePower();
        if(index<0 || index>=slots.size()) return ItemStack.EMPTY;
        Slot slot=slots.get(index); if(!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack=slot.getItem(), before=stack.copy();
        boolean moved = index<2 ? moveItemStackTo(stack,2,slots.size(),true) : moveItemStackTo(stack,0,2,false);
        if(!moved) return ItemStack.EMPTY;
        if(stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player,stack); return before;
    }
}
