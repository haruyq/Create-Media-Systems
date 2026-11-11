package org.haruyq.createmediasystems;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public abstract class HeldItemMenu extends AbstractContainerMenu {
    private static final int OFFHAND = 40;
    protected final Inventory inventory;
    /** The inventory slot the item is held in. */
    protected final int held;
    protected final ItemStack item;
    private final Class<? extends Item> kind;

    @FunctionalInterface
    public interface Factory {
        AbstractContainerMenu create(int menuId, Inventory inventory, int held);
    }

    protected HeldItemMenu(MenuType<?> type, int id, Inventory inventory, int held, Class<? extends Item> kind) {
        super(type, id);
        this.inventory = inventory;
        this.held = held;
        this.item = inventory.getItem(held);
        this.kind = kind;
    }

    public static void open(ServerPlayer player, InteractionHand hand, Factory factory) {
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : OFFHAND;
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> factory.create(id, inventory, slot),
                player.getItemInHand(hand).getHoverName()), buf -> buf.writeVarInt(slot));
    }

    protected void addPlayerSlots(int x, int y) {
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addPlayerSlot(col + row * 9 + 9, x + col * 18, y + row * 18);
        for (int col = 0; col < 9; col++) addPlayerSlot(col, x + col * 18, y + 58);
    }

    private void addPlayerSlot(int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override public boolean mayPickup(Player player) { return index != held; }
            @Override public boolean mayPlace(ItemStack stack) { return index != held; }
        });
    }

    @Override
    public boolean stillValid(Player player) {
        return held >= 0 && held < inventory.getContainerSize() && inventory.getItem(held) == item && kind.isInstance(item.getItem());
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (type == ClickType.SWAP && button == held) return;
        if (slot >= 0 && slot < slots.size() && slots.get(slot).container == inventory && slots.get(slot).getContainerSlot() == held) return;
        super.clicked(slot, button, type, player);
    }
}
