package org.haruyq.createmediasystems.power;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A replaceable BATTERY400-style pack that portable speakers run on. Crafting makes it empty; the charger fills it. */
public final class BatteryItem extends Item implements ChargeableItem {
    /** 68 Wh, in FE. */
    public static final int CAPACITY = 244800;

    public BatteryItem(Properties properties) { super(properties.stacksTo(1)); }

    @Override public int capacity() { return CAPACITY; }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return ChargeableItem.barWidth(stack); }
    @Override public int getBarColor(ItemStack stack) { return ChargeableItem.energy(stack) == 0 ? 0xF04E38 : 0x67D8A0; }
}
