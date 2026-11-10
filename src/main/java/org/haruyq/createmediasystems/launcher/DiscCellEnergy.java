package org.haruyq.createmediasystems.launcher;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Receive-only NeoForge energy: simulation is side-effect free and no energy is created by swapping cells. */
public final class DiscCellEnergy implements IEnergyStorage {
    private final ItemStack cell;
    public DiscCellEnergy(ItemStack cell) { this.cell = cell; }
    @Override public int receiveEnergy(int maxReceive, boolean simulate) {
        int accepted = Math.min(Math.max(0, maxReceive), getMaxEnergyStored() - getEnergyStored());
        if (!simulate && accepted > 0) DiscCellItem.setEnergy(cell, getEnergyStored() + accepted);
        return accepted;
    }
    @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return DiscCellItem.energy(cell); }
    @Override public int getMaxEnergyStored() {
        return cell.getItem() instanceof DiscCellItem item ? item.tier.capacity() : 0;
    }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return getEnergyStored() < getMaxEnergyStored(); }
}
