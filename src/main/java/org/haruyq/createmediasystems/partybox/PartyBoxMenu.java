package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.FittingSlot;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.haruyq.createmediasystems.stream.PortablePlaylist;
import org.jetbrains.annotations.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class PartyBoxMenu extends AbstractContainerMenu {
    @Nullable private final PartyBoxEntity serverBox;
    private final Level level;
    private final int entity;

    public PartyBoxMenu(int id, Inventory player, RegistryFriendlyByteBuf data) {
        this(id, player, data.readVarInt(), null);
    }

    public PartyBoxMenu(int id, Inventory player, PartyBoxEntity box) {
        this(id, player, box.getId(), box);
    }

    private PartyBoxMenu(int id, Inventory player, int entity, @Nullable PartyBoxEntity box) {
        super(PartyBoxFeature.PARTYBOX_MENU.get(), id);
        serverBox = box;
        level = player.player.level();
        this.entity = entity;
        var storage = box == null ? new SimpleContainer(2) : box.inventory;
        addSlot(new FittingSlot(storage, 0, 25, 58, PortablePlaylist::fits));
        addSlot(new FittingSlot(storage, 1, 25, 109, BatteryItem::fits));
        for(int row=0;row<3;row++) for(int col=0;col<9;col++) addSlot(new Slot(player,9+row*9+col,116+col*18,152+row*18));
        for(int col=0;col<9;col++) addSlot(new Slot(player,col,116+col*18,210));
    }

    /** The menu can arrive a tick before the client starts tracking the trolley. */
    @Nullable
    public PartyBoxEntity box() {
        return serverBox != null ? serverBox
                : level.getEntity(entity) instanceof PartyBoxEntity box && !box.isRemoved() ? box : null;
    }

    @Override public boolean stillValid(Player player) {
        return level.isClientSide || serverBox != null && !serverBox.isRemoved() && serverBox.distanceToSqr(player) < 64;
    }

    @Override public void clicked(int slot,int button,net.minecraft.world.inventory.ClickType type,Player player){
        if(serverBox!=null)serverBox.settlePower();super.clicked(slot,button,type,player);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if(serverBox!=null)serverBox.settlePower();
        if(index<0 || index>=slots.size()) return ItemStack.EMPTY;
        Slot slot=slots.get(index); if(!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack=slot.getItem(), before=stack.copy();
        boolean moved = index<2 ? moveItemStackTo(stack,2,slots.size(),true) : moveItemStackTo(stack,0,2,false);
        if(!moved) return ItemStack.EMPTY;
        if(stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player,stack); return before;
    }
}
