package org.haruyq.createmediasystems;

import java.util.function.Predicate;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A menu slot holding one item that {@code fits}, a rule its item's model owns (MediaItem#fits, BatteryItem#fits).
 * Shift-clicks follow it too: moveItemStackTo puts a stack only where it may be placed.
 */
public final class FittingSlot extends Slot {
    private final Predicate<ItemStack> fits;

    public FittingSlot(Container container, int index, int x, int y, Predicate<ItemStack> fits) {
        super(container, index, x, y);
        this.fits = fits;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return fits.test(stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
