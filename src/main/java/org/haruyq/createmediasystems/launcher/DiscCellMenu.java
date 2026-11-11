package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.HeldItemMenu;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The held cell is locked in place; every master-slot change commits to its server-owned component. */
public final class DiscCellMenu extends HeldItemMenu {
    private final SimpleContainer master;
    private final DataSlot startSeconds;

    public DiscCellMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) { this(id, inventory, data.readVarInt()); }

    public DiscCellMenu(int id, Inventory inventory, int cellSlot) {
        super(LauncherFeature.DISC_CELL_MENU.get(), id, inventory, cellSlot, DiscCellItem.class);
        startSeconds = addDataSlot(new DataSlot() {
            private int synchronizedSeconds = DiscCellItem.startSeconds(item);

            @Override
            public int get() {
                return inventory.player.level().isClientSide ? synchronizedSeconds : DiscCellItem.startSeconds(item);
            }

            @Override
            public void set(int value) {
                synchronizedSeconds = value;
            }
        });
        this.master = new SimpleContainer(1);
        master.setItem(0, DiscCellItem.master(item).copy());
        master.addListener(container -> {
            if (!inventory.player.level().isClientSide && stillValid(inventory.player)) {
                DiscCellItem.setMaster(item, master.getItem(0));
                inventory.setChanged();
            }
        });
        addSlot(new Slot(master, 0, 80, 45) {
            @Override public boolean mayPlace(ItemStack stack) { return MediaItem.fits(stack, DiscKind.Slot.PORTABLE); }
            @Override public int getMaxStackSize() { return 1; }
        });
        addPlayerSlots(8, 134);
    }

    public ItemStack cell() { return item; }

    public int startSeconds() { return startSeconds.get(); }

    public ItemStack masterDisc() { return master.getItem(0); }

    public boolean hasPlayableMaster() { return DiscCellItem.isPlayableMaster(masterDisc()); }

    @Override
    public boolean clickMenuButton(Player player, int seconds) {
        if (player.level().isClientSide || player != inventory.player || player.isSpectator()
                || !stillValid(player) || !hasPlayableMaster()
                || seconds < 0 || seconds > DiscCellItem.MAX_START_SECONDS) {
            return false;
        }
        if (!DiscPlayback.canStartAt(player.level(), masterDisc(), seconds)) {
            player.displayClientMessage(Component.translatable("cell.create_media_systems.start_past_end"), true);
            return false;
        }
        DiscCellItem.setStartSeconds(item, seconds);
        inventory.setChanged();
        return true;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!MediaItem.fits(stack, DiscKind.Slot.PORTABLE) || !moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        return before;
    }
}
