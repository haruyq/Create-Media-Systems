package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

final class AdapterFuel {
    private ItemStack fuel = ItemStack.EMPTY;
    private ItemStack remainder = ItemStack.EMPTY;
    private int remaining;

    static boolean accepts(ItemStack stack) {
        return stack.getBurnTime(RecipeType.SMELTING) > 0;
    }

    int insert(ItemStack stack) {
        if (!accepts(stack) || !fuel.isEmpty() && !ItemStack.isSameItemSameComponents(fuel, stack)) return 0;
        int count = Math.min(stack.getCount(), stack.getMaxStackSize() - fuel.getCount());
        if (count <= 0) return 0;
        if (fuel.isEmpty()) fuel = stack.copyWithCount(count);
        else fuel.grow(count);
        return count;
    }

    ItemStack take() {
        if (!remainder.isEmpty()) {
            var stack = remainder;
            remainder = ItemStack.EMPTY;
            return stack;
        }
        var stack = fuel;
        fuel = ItemStack.EMPTY;
        return stack;
    }

    int generate(int room) {
        if (room < KeyboardPowerAdapter.TRANSFER) return 0;
        if (remaining == 0) {
            int ticks = fuel.getBurnTime(RecipeType.SMELTING);
            if (ticks <= 0 || !remainder.isEmpty()) return 0;
            remainder = fuel.getCraftingRemainingItem();
            fuel.shrink(1);
            remaining = ticks;
        }
        remaining--;
        return KeyboardPowerAdapter.TRANSFER;
    }

    int remaining() { return remaining; }
    boolean hasContents() { return !fuel.isEmpty() || !remainder.isEmpty(); }

    void addDrops(List<ItemStack> drops) {
        if (!fuel.isEmpty()) drops.add(fuel.copy());
        if (!remainder.isEmpty()) drops.add(remainder.copy());
    }

    void write(CompoundTag tag, HolderLookup.Provider registries) {
        if (fuel.isEmpty()) tag.remove("Fuel");
        else tag.put("Fuel", fuel.save(registries));
        if (remainder.isEmpty()) tag.remove("FuelRemainder");
        else tag.put("FuelRemainder", remainder.save(registries));
        tag.putInt("BurnTicks", remaining);
    }

    void read(CompoundTag tag, HolderLookup.Provider registries) {
        fuel = ItemStack.parseOptional(registries, tag.getCompound("Fuel"));
        remainder = ItemStack.parseOptional(registries, tag.getCompound("FuelRemainder"));
        remaining = Math.max(0, tag.getInt("BurnTicks"));
    }
}
