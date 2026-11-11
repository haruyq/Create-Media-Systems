package org.haruyq.createmediasystems.launcher;

import java.util.ArrayList;
import java.util.List;
import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Installed and pending cells are owned by the launcher, including during an interrupted reload. */
public final class LauncherMagazine {
    private LauncherMagazine() {}
    public static boolean isDisc(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(AudioFeature.CUSTOM_DISC.get())
                || stack.is(VideoFeature.DVD.get()) || stack.is(VideoFeature.BLU_RAY.get())
                || stack.has(DataComponents.JUKEBOX_PLAYABLE));
    }
    public static ItemStack cell(ItemStack launcher) {
        return launcher.getOrDefault(LauncherFeature.LAUNCHER_CELL.get(), ItemContainerContents.EMPTY).copyOne();
    }
    public static ItemStack pending(ItemStack launcher) {
        return launcher.getOrDefault(LauncherFeature.LAUNCHER_PENDING_CELL.get(), ItemContainerContents.EMPTY).copyOne();
    }
    public static void setCell(ItemStack launcher, ItemStack cell) {
        if (cell.isEmpty()) launcher.remove(LauncherFeature.LAUNCHER_CELL.get());
        else launcher.set(LauncherFeature.LAUNCHER_CELL.get(), ItemContainerContents.fromItems(List.of(cell.copyWithCount(1))));
    }
    public static List<ItemStack> reserved(ItemStack launcher) {
        return pending(launcher).isEmpty() ? List.of() : List.of(pending(launcher));
    }
    public static boolean reloading(ItemStack launcher) { return launcher.has(LauncherFeature.LAUNCHER_RELOAD_START.get()); }
    public static boolean ejecting(ItemStack launcher) { return launcher.getOrDefault(LauncherFeature.LAUNCHER_EJECT_ONLY.get(), false); }
    public static int count(ItemStack launcher) { return DiscCellItem.shots(cell(launcher)); }
    public static DiscCellTier tier(ItemStack launcher) {
        return cell(launcher).getItem() instanceof DiscCellItem item ? item.tier : DiscCellTier.COPPER;
    }
    public static boolean reserve(Player player, ItemStack launcher, long gameTick) {
        return reserve(player,launcher,gameTick,false);
    }
    public static boolean reserve(Player player,ItemStack launcher,long gameTick,boolean automatic) {
        if (reloading(launcher)) return false;
        if (automatic && count(launcher)>0) return false;
        migrateLegacy(player, launcher);
        int chosen = -1;
        for (int pass = 0; pass < 2 && chosen < 0; pass++) {
            for (int n = 0; n < player.getInventory().getContainerSize(); n++) {
                int slot = n == 0 ? 40 : n <= 40 ? n - 1 : n;
                ItemStack candidate = player.getInventory().getItem(slot);
                if (candidate.getItem() instanceof DiscCellItem && DiscCellItem.hasMaster(candidate)
                        && (!automatic || DiscCellItem.shots(candidate)>0)
                        && (pass == 1 || DiscCellItem.shots(candidate) > 0)) { chosen = slot; break; }
            }
        }
        if (chosen < 0) return false;
        ItemStack escrow = player.getInventory().removeItem(chosen, 1);
        if (escrow.isEmpty()) return false;
        launcher.set(LauncherFeature.LAUNCHER_PENDING_CELL.get(), ItemContainerContents.fromItems(List.of(escrow)));
        launcher.remove(LauncherFeature.LAUNCHER_EJECT_ONLY.get());
        launcher.set(LauncherFeature.LAUNCHER_RELOAD_START.get(), gameTick);
        player.stopUsingItem();
        player.getInventory().setChanged();
        return true;
    }
    public static boolean eject(Player player, ItemStack launcher, long gameTick) {
        if (reloading(launcher) || cell(launcher).isEmpty()) return false;
        launcher.set(LauncherFeature.LAUNCHER_EJECT_ONLY.get(), true);
        launcher.set(LauncherFeature.LAUNCHER_RELOAD_START.get(), gameTick);
        player.stopUsingItem();
        return true;
    }
    public static boolean finish(Player player, ItemStack launcher) {
        if (!reloading(launcher)) return false;
        ItemStack previous = cell(launcher).copy();
        ItemStack next = ejecting(launcher) ? ItemStack.EMPTY : pending(launcher).copy();
        launcher.remove(LauncherFeature.LAUNCHER_PENDING_CELL.get());
        launcher.remove(LauncherFeature.LAUNCHER_RELOAD_START.get());
        launcher.remove(LauncherFeature.LAUNCHER_EJECT_ONLY.get());
        setCell(launcher, next);
        if (!previous.isEmpty()) player.getInventory().placeItemBackInInventory(previous);
        player.getInventory().setChanged();
        return true;
    }
    public static ItemStack pop(ItemStack launcher) {
        if (reloading(launcher)) return ItemStack.EMPTY;
        ItemStack next = cell(launcher).copy();
        ItemStack projection = DiscCellItem.project(next);
        if (!projection.isEmpty()) setCell(launcher, next);
        return projection;
    }
    /** Refund pre-cell ammunition once, without losing any original disc components. */
    public static void migrateLegacy(Player player, ItemStack launcher) {
        if (!launcher.has(LauncherFeature.LAUNCHER_LOADED.get()) && !launcher.has(LauncherFeature.LAUNCHER_RESERVED.get())) return;
        List<ItemStack> originals = new ArrayList<>(launcher.getOrDefault(LauncherFeature.LAUNCHER_LOADED.get(), List.of()));
        originals.addAll(launcher.getOrDefault(LauncherFeature.LAUNCHER_RESERVED.get(), List.of()));
        launcher.remove(LauncherFeature.LAUNCHER_LOADED.get());
        launcher.remove(LauncherFeature.LAUNCHER_RESERVED.get());
        if (pending(launcher).isEmpty() && !ejecting(launcher)) launcher.remove(LauncherFeature.LAUNCHER_RELOAD_START.get());
        originals.forEach(item -> player.getInventory().placeItemBackInInventory(item.copy()));
    }
}
