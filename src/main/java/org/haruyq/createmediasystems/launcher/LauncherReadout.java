package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.power.ChargeableItem;
import net.minecraft.world.item.ItemStack;

/** The installed cell is authoritative until the server commits the reload. */
public record LauncherReadout(boolean hasCell, DiscCellTier tier, int shots, int capacity,
                              float charge, boolean hasOriginal) {
    public static LauncherReadout of(ItemStack launcher) {
        ItemStack cell = LauncherMagazine.cell(launcher);
        if (!(cell.getItem() instanceof DiscCellItem item))
            return new LauncherReadout(false, DiscCellTier.COPPER, 0, 0, 0, false);
        return new LauncherReadout(true, item.tier, DiscCellItem.shots(cell), item.tier.shots,
                ChargeableItem.charge(cell), DiscCellItem.hasMaster(cell));
    }
}
