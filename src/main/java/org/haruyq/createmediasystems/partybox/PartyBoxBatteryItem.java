package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.launcher.LauncherFeature;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Replaceable BATTERY400-style pack. Crafting creates an empty pack; FE/Create rotation charges it. */
public final class PartyBoxBatteryItem extends Item {
    public PartyBoxBatteryItem(Properties properties) { super(properties.stacksTo(1)); }
    public static int energy(ItemStack stack) { return stack.getItem() instanceof PartyBoxBatteryItem
            ? Math.clamp(stack.getOrDefault(LauncherFeature.CELL_ENERGY.get(), 0), 0, PartyBoxSpecs.CAPACITY_FE) : 0; }
    public static void setEnergy(ItemStack stack, int energy) {
        if (stack.getItem() instanceof PartyBoxBatteryItem) stack.set(LauncherFeature.CELL_ENERGY.get(), Math.clamp(energy, 0, PartyBoxSpecs.CAPACITY_FE));
    }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13f * energy(stack) / PartyBoxSpecs.CAPACITY_FE); }
    @Override public int getBarColor(ItemStack stack) { return energy(stack) == 0 ? 0xF04E38 : 0x67D8A0; }
    public static IEnergyStorage storage(ItemStack stack) {
        return new IEnergyStorage() {
            public int receiveEnergy(int max, boolean simulate) {
                int accepted = Math.min(Math.max(0, max), PartyBoxSpecs.CAPACITY_FE - energy(stack));
                if (!simulate && accepted > 0) setEnergy(stack, energy(stack) + accepted);
                return accepted;
            }
            public int extractEnergy(int max, boolean simulate) { return 0; }
            public int getEnergyStored() { return energy(stack); }
            public int getMaxEnergyStored() { return PartyBoxSpecs.CAPACITY_FE; }
            public boolean canExtract() { return false; }
            public boolean canReceive() { return energy(stack) < PartyBoxSpecs.CAPACITY_FE; }
        };
    }
}
