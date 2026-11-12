package org.haruyq.createmediasystems.power;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.haruyq.createmediasystems.launcher.DiscCellCharger;

/** A replaceable BATTERY400-style pack that portable speakers run on. Crafting makes it empty; the charger fills it. */
public final class BatteryItem extends Item implements ChargeableItem {
    /** 68 Wh, in FE. */
    public static final int CAPACITY = 244800;

    public BatteryItem(Properties properties) { super(properties.stacksTo(1)); }

    /** Whether a portable speaker's battery slot takes {@code stack}: a battery, never another chargeable item. */
    public static boolean fits(ItemStack stack) { return stack.getItem() instanceof BatteryItem; }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        var level = context.getLevel();
        if (player == null || !player.isShiftKeyDown()
                || !(level.getBlockEntity(context.getClickedPos()) instanceof DiscCellCharger charger)) {
            return super.useOn(context);
        }
        if (!level.isClientSide) charger.supplyReserve(player, context.getItemInHand());
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public int capacity() { return CAPACITY; }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return ChargeableItem.barWidth(stack); }
    @Override public int getBarColor(ItemStack stack) { return ChargeableItem.energy(stack) == 0 ? 0xF04E38 : 0x67D8A0; }
}
