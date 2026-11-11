package org.haruyq.createmediasystems.launcher;

import java.util.List;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A rechargeable, removable cartridge. Its original disc never leaves this one-slot store when firing. */
public final class DiscCellItem extends Item {
    public static final int MAX_START_SECONDS = 24 * 60 * 60;

    public final DiscCellTier tier;

    public DiscCellItem(DiscCellTier tier, Properties properties) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public static ItemStack master(ItemStack cell) {
        return cell.getOrDefault(CreateMediaSystems.CELL_MASTER.get(), ItemContainerContents.EMPTY).copyOne();
    }

    public static void setMaster(ItemStack cell, ItemStack master) {
        if (!master.isEmpty() && !LauncherMagazine.isDisc(master)) {
            throw new IllegalArgumentException("A cell accepts only a media disc");
        }
        if (!ItemStack.isSameItemSameComponents(master(cell), master)) {
            cell.remove(CreateMediaSystems.CELL_START_SECONDS.get());
        }
        if (master.isEmpty()) cell.remove(CreateMediaSystems.CELL_MASTER.get());
        else {
            cell.set(CreateMediaSystems.CELL_MASTER.get(), ItemContainerContents.fromItems(List.of(master.copyWithCount(1))));
        }
    }

    public static int startSeconds(ItemStack cell) {
        return Math.clamp(cell.getOrDefault(CreateMediaSystems.CELL_START_SECONDS.get(), 0), 0, MAX_START_SECONDS);
    }

    static void setStartSeconds(ItemStack cell, int seconds) {
        if (seconds == 0) cell.remove(CreateMediaSystems.CELL_START_SECONDS.get());
        else cell.set(CreateMediaSystems.CELL_START_SECONDS.get(), seconds);
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

    public static int energy(ItemStack cell) {
        return cell.getItem() instanceof DiscCellItem item
                ? Math.clamp(cell.getOrDefault(CreateMediaSystems.CELL_ENERGY.get(), 0), 0, item.tier.capacity()) : 0;
    }

    public static void setEnergy(ItemStack cell, int energy) {
        if (cell.getItem() instanceof DiscCellItem item)
            cell.set(CreateMediaSystems.CELL_ENERGY.get(), Math.clamp(energy, 0, item.tier.capacity()));
    }

    public static int shots(ItemStack cell) {
        return cell.getItem() instanceof DiscCellItem item ? energy(cell) / item.tier.energyPerShot : 0;
    }

    public static boolean hasMaster(ItemStack cell) {
        return isPlayableMaster(master(cell));
    }

    static boolean isPlayableMaster(ItemStack disc) {
        if (!LauncherMagazine.isDisc(disc)) return false;
        String url = DiscPlayback.mediaUrl(disc);
        return !(disc.is(CreateMediaSystems.CUSTOM_DISC.get()) || disc.is(CreateMediaSystems.DVD.get())
                || disc.is(CreateMediaSystems.BLU_RAY.get())) || url != null && !url.isBlank();
    }

    /** Makes a projection, spending exactly one shot's energy. Does not modify the original or its playback state. */
    public static ItemStack project(ItemStack cell) {
        if (!(cell.getItem() instanceof DiscCellItem item) || !hasMaster(cell) || shots(cell) == 0) return ItemStack.EMPTY;
        ItemStack projection = master(cell).copyWithCount(1);
        projection.remove(CreateMediaSystems.DISC_PLAYBACK.get());
        setEnergy(cell, energy(cell) - item.tier.energyPerShot);
        return projection;
    }

    public static ItemStack upgrade(ItemStack previous, DiscCellTier target) {
        ItemStack result = new ItemStack(CreateMediaSystems.cell(target));
        setMaster(result, master(previous));
        setEnergy(result, energy(previous));
        setStartSeconds(result, startSeconds(previous));
        return result;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
            server.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new DiscCellMenu(id, inventory, slot),
                    held.getHoverName()), buf -> buf.writeVarInt(slot));
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13f * energy(stack) / tier.capacity()); }
    @Override public int getBarColor(ItemStack stack) { return shots(stack) == 0 ? 0xDE5A54 : tier.color; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("cell.create_media_systems.stats", tier.level, shots(stack), tier.shots,
                (int) tier.damage).withStyle(ChatFormatting.AQUA));
        ItemStack master = master(stack);
        tooltip.add(master.isEmpty() ? Component.translatable("cell.create_media_systems.empty").withStyle(ChatFormatting.YELLOW)
                : Component.translatable("cell.create_media_systems.master", master.getOrDefault(CreateMediaSystems.TITLE.get(),
                        master.getHoverName().getString())).withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.translatable("cell.create_media_systems.energy", energy(stack), tier.capacity())
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
