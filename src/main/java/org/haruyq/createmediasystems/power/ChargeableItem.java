package org.haruyq.createmediasystems.power;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * An item that keeps FE in its {@link PowerFeature#ENERGY} component, up to its capacity. FE cables and the cell charger
 * only ever charge it; what it powers spends its energy through {@link #setEnergy}.
 */
public interface ChargeableItem {
    /** The most FE one of these holds. */
    int capacity();

    static int energy(ItemStack stack) {
        return stack.getItem() instanceof ChargeableItem item
                ? Math.clamp(stack.getOrDefault(PowerFeature.ENERGY.get(), 0), 0, item.capacity()) : 0;
    }

    static void setEnergy(ItemStack stack, int energy) {
        if (stack.getItem() instanceof ChargeableItem item) {
            stack.set(PowerFeature.ENERGY.get(), Math.clamp(energy, 0, item.capacity()));
        }
    }

    /** How full {@code stack} is, 0..1; 0 for any other item. */
    static float charge(ItemStack stack) {
        return stack.getItem() instanceof ChargeableItem item ? (float) energy(stack) / item.capacity() : 0;
    }

    static int barWidth(ItemStack stack) {
        return Math.round(13f * charge(stack));
    }

    /** {@code stack} as receive-only FE storage: simulating changes nothing, and nothing can be drawn out. */
    static IEnergyStorage storage(ItemStack stack) {
        return new IEnergyStorage() {
            @Override public int receiveEnergy(int max, boolean simulate) {
                int accepted = Math.min(Math.max(0, max), getMaxEnergyStored() - getEnergyStored());
                if (!simulate && accepted > 0) setEnergy(stack, getEnergyStored() + accepted);
                return accepted;
            }
            @Override public int extractEnergy(int max, boolean simulate) { return 0; }
            @Override public int getEnergyStored() { return energy(stack); }
            @Override public int getMaxEnergyStored() {
                return stack.getItem() instanceof ChargeableItem item ? item.capacity() : 0;
            }
            @Override public boolean canExtract() { return false; }
            @Override public boolean canReceive() { return getEnergyStored() < getMaxEnergyStored(); }
        };
    }
}
