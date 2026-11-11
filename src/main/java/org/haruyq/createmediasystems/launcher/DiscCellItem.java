package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.HeldItemMenu;
import org.haruyq.createmediasystems.power.ChargeableItem;
import java.util.List;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.stream.MediaItem;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A rechargeable, removable cartridge. Its original disc never leaves this one-slot store when firing. */
public final class DiscCellItem extends Item implements ChargeableItem {
    public static final int MAX_START_SECONDS = (int) MediaItem.MAX_LENGTH_SECONDS;

    public final DiscCellTier tier;

    public DiscCellItem(DiscCellTier tier, Properties properties) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public static ItemStack master(ItemStack cell) {
        return cell.getOrDefault(LauncherFeature.CELL_MASTER.get(), ItemContainerContents.EMPTY).copyOne();
    }

    public static void setMaster(ItemStack cell, ItemStack master) {
        if (!master.isEmpty() && !MediaItem.fits(master, DiscKind.Slot.PORTABLE)) {
            throw new IllegalArgumentException("A cell accepts only a media disc");
        }
        if (!ItemStack.isSameItemSameComponents(master(cell), master)) {
            cell.remove(LauncherFeature.CELL_START_SECONDS.get());
        }
        if (master.isEmpty()) cell.remove(LauncherFeature.CELL_MASTER.get());
        else {
            cell.set(LauncherFeature.CELL_MASTER.get(), ItemContainerContents.fromItems(List.of(master.copyWithCount(1))));
        }
    }

    public static int startSeconds(ItemStack cell) {
        return Math.clamp(cell.getOrDefault(LauncherFeature.CELL_START_SECONDS.get(), 0), 0, MAX_START_SECONDS);
    }

    static void setStartSeconds(ItemStack cell, int seconds) {
        if (seconds == 0) cell.remove(LauncherFeature.CELL_START_SECONDS.get());
        else cell.set(LauncherFeature.CELL_START_SECONDS.get(), seconds);
    }

    public static String formatStartTime(int seconds) {
        return StringUtil.formatTickDuration(seconds * 20, 20.0F);
    }

    public static int parseStartTime(String text) {
        String[] fields = text.strip().split(":", -1);
        if (fields.length > 3) return -1;
        int seconds = 0;
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            if (field.isEmpty() || field.length() > 5 || !field.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return -1;
            }
            int value = Integer.parseInt(field);
            if (i > 0 && value >= 60) return -1;
            seconds = seconds * 60 + value;
            if (seconds > MAX_START_SECONDS) return -1;
        }
        return seconds;
    }

    @Override public int capacity() { return tier.capacity(); }

    public static int shots(ItemStack cell) {
        return cell.getItem() instanceof DiscCellItem item ? ChargeableItem.energy(cell) / item.tier.energyPerShot : 0;
    }

    public static boolean hasMaster(ItemStack cell) {
        return isPlayableMaster(master(cell));
    }

    static boolean isPlayableMaster(ItemStack disc) {
        return MediaItem.fits(disc, DiscKind.Slot.PORTABLE) && !MediaItem.silent(disc);
    }

    /** Makes a projection, spending exactly one shot's energy. Does not modify the original or its playback state. */
    public static ItemStack project(ItemStack cell) {
        if (!(cell.getItem() instanceof DiscCellItem item) || !hasMaster(cell) || shots(cell) == 0) return ItemStack.EMPTY;
        ItemStack projection = master(cell).copyWithCount(1);
        projection.remove(LauncherFeature.DISC_PLAYBACK.get());
        ChargeableItem.setEnergy(cell, ChargeableItem.energy(cell) - item.tier.energyPerShot);
        return projection;
    }

    public static ItemStack upgrade(ItemStack previous, DiscCellTier target) {
        ItemStack result = new ItemStack(LauncherFeature.cell(target));
        setMaster(result, master(previous));
        ChargeableItem.setEnergy(result, ChargeableItem.energy(previous));
        setStartSeconds(result, startSeconds(previous));
        return result;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) HeldItemMenu.open(server, hand, DiscCellMenu::new);
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return ChargeableItem.barWidth(stack); }
    @Override public int getBarColor(ItemStack stack) { return shots(stack) == 0 ? 0xDE5A54 : tier.color; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("cell.create_media_systems.stats", tier.level, shots(stack), tier.shots,
                (int) tier.damage).withStyle(ChatFormatting.AQUA));
        ItemStack master = master(stack);
        tooltip.add(master.isEmpty() ? Component.translatable("cell.create_media_systems.empty").withStyle(ChatFormatting.YELLOW)
                : Component.translatable("cell.create_media_systems.master", master.getOrDefault(AudioFeature.TITLE.get(),
                        master.getHoverName().getString())).withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.translatable("cell.create_media_systems.energy", ChargeableItem.energy(stack), capacity())
                .withStyle(ChatFormatting.GRAY));
        int seconds = startSeconds(stack);
        if (seconds > 0) {
            tooltip.add(Component.translatable("cell.create_media_systems.start_at", formatStartTime(seconds))
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("cell.create_media_systems.controls").withStyle(ChatFormatting.DARK_GRAY));
        if (tier.optional()) tooltip.add(Component.translatable("cell.create_media_systems.chemi").withStyle(ChatFormatting.GREEN));
    }
}
