package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.FittingSlot;
import org.haruyq.createmediasystems.HeldItemMenu;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The held cell is locked in place; every master-slot change commits to its server-owned component. */
public final class DiscCellMenu extends HeldItemMenu {
    private final SimpleContainer master;

    public DiscCellMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) { this(id, inventory, data.readVarInt()); }

    public DiscCellMenu(int id, Inventory inventory, int cellSlot) {
        super(LauncherFeature.DISC_CELL_MENU.get(), id, inventory, cellSlot, DiscCellItem.class);
        this.master = new SimpleContainer(1);
        master.setItem(0, DiscCellItem.master(item).copy());
        master.addListener(container -> {
            if (!inventory.player.level().isClientSide && stillValid(inventory.player)) {
                DiscCellItem.setMaster(item, master.getItem(0));
                inventory.setChanged();
            }
        });
        addSlot(new FittingSlot(master, 0, 80, 45, stack -> MediaItem.fits(stack, DiscKind.Slot.PORTABLE)));
        addPlayerSlots(8, 102);
    }

    public ItemStack cell() { return item; }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        return before;
    }
}
