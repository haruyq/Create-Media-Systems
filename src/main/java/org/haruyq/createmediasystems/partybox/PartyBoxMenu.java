package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.launcher.LauncherMagazine;
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
        super(CreateMediaSystems.PARTYBOX_MENU.get(), id); this.box=box;
        var storage = box == null ? new net.minecraft.world.SimpleContainer(2) : box.inventory;
        addSlot(new Slot(storage, 0, 25, 58) {
            @Override public boolean mayPlace(ItemStack item) { return LauncherMagazine.isDisc(item); }
            @Override public int getMaxStackSize() { return 1; }
        });
        addSlot(new Slot(storage, 1, 25, 109) {
            @Override public boolean mayPlace(ItemStack item) { return item.getItem() instanceof PartyBoxBatteryItem; }
            @Override public int getMaxStackSize() { return 1; }
        });
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
        boolean moved = index<2 ? moveItemStackTo(stack,2,slots.size(),true)
                : stack.getItem() instanceof PartyBoxBatteryItem ? moveItemStackTo(stack,1,2,false)
                : LauncherMagazine.isDisc(stack) && moveItemStackTo(stack,0,1,false);
        if(!moved) return ItemStack.EMPTY;
        if(stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player,stack); return before;
    }
}
