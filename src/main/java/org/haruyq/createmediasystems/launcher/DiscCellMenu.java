package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The held cell is locked in place; every master-slot change commits to its server-owned component. */
public final class DiscCellMenu extends AbstractContainerMenu {
    private final Inventory inventory;
    private final int cellSlot;
    private final ItemStack cell;
    private final SimpleContainer master;
    private final DataSlot startSeconds;

    public DiscCellMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) { this(id, inventory, data.readVarInt()); }

    public DiscCellMenu(int id, Inventory inventory, int cellSlot) {
        super(CreateMediaSystems.DISC_CELL_MENU.get(), id);
        this.inventory = inventory;
        this.cellSlot = cellSlot;
        this.cell = inventory.getItem(cellSlot);
        startSeconds = addDataSlot(new DataSlot() {
            private int synchronizedSeconds = DiscCellItem.startSeconds(cell);

            @Override
            public int get() {
                return inventory.player.level().isClientSide ? synchronizedSeconds : DiscCellItem.startSeconds(cell);
            }

            @Override
            public void set(int value) {
                synchronizedSeconds = value;
            }
        });
        this.master = new SimpleContainer(1);
        master.setItem(0, DiscCellItem.master(cell).copy());
        master.addListener(container -> {
            if (!inventory.player.level().isClientSide && stillValid(inventory.player)) {
                DiscCellItem.setMaster(cell, master.getItem(0));
                inventory.setChanged();
            }
        });
        addSlot(new Slot(master, 0, 80, 45) {
            @Override public boolean mayPlace(ItemStack stack) { return LauncherMagazine.isDisc(stack); }
            @Override public int getMaxStackSize() { return 1; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addPlayerSlot(col + row * 9 + 9, 8 + col * 18, 134 + row * 18);
        for (int col = 0; col < 9; col++) addPlayerSlot(col, 8 + col * 18, 192);
    }

    private void addPlayerSlot(int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override public boolean mayPickup(Player player) { return index != cellSlot; }
            @Override public boolean mayPlace(ItemStack stack) { return index != cellSlot; }
        });
    }

    public ItemStack cell() { return cell; }

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
        DiscCellItem.setStartSeconds(cell, seconds);
        inventory.setChanged();
        return true;
    }

    @Override public boolean stillValid(Player player) {
        return inventory.getItem(cellSlot) == cell && cell.getItem() instanceof DiscCellItem;
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (type == ClickType.SWAP && button == cellSlot) return;
        if (slot >= 1 && slot < slots.size() && slots.get(slot).getContainerSlot() == cellSlot) return;
        super.clicked(slot, button, type, player);
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
            if (!LauncherMagazine.isDisc(stack) || !moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        return before;
    }
}
