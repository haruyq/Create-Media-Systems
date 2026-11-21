package org.haruyq.createmediasystems.items.instrument;

import java.util.List;

import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.items.power.ChargeableItem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class InstrumentItem extends Item {
    public final Kind kind;

    InstrumentItem(Kind kind) {
        super(new Properties().stacksTo(1));
        this.kind = kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (kind == Kind.STRATOCASTER && player.isShiftKeyDown() && !GuitarPatch.get(stack).links().isEmpty()) {
            if (!level.isClientSide) {
                stack.remove(InstrumentFeature.PATCH.get());
                player.displayClientMessage(Component.translatable("guitar_patch.create_media_systems.cleared"), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (!canPlay(player, hand)) {
            return InteractionResultHolder.pass(stack);
        }
        if (player instanceof ServerPlayer server) WearableInstruments.begin(server, hand, kind);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        var pos = context.getClickedPos();
        var player = context.getPlayer();
        if (kind != Kind.STRATOCASTER || player == null
                || !(level.getBlockState(pos).getBlock() instanceof AutoPedalBlock pedal)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            GuitarPatch.toggle(context.getItemInHand(), player,
                    new GuitarPatch.Link(GlobalPos.of(level.dimension(), pos), pedal.effect()));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (kind != Kind.STRATOCASTER) return;
        var links = GuitarPatch.get(stack).links();
        if (links.isEmpty()) {
            tooltip.add(Component.translatable("guitar_patch.create_media_systems.hint").withStyle(ChatFormatting.GRAY));
        } else {
            for (int i = 0; i < links.size(); i++) {
                var link = links.get(i);
                tooltip.add(Component.translatable("guitar_patch.create_media_systems.link", i + 1, link.effect().displayName(),
                        link.location().pos().toShortString()).withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(Component.translatable("guitar_patch.create_media_systems.clear_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public boolean canPlay(Player player, InteractionHand hand) {
        if (kind == Kind.KEYTAR) return hand == InteractionHand.MAIN_HAND;
        if (kind == Kind.MELODICA) return !WearableInstruments.ready(player);
        InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return player.getItemInHand(other).isEmpty();
    }

    public static final class TwoHanded extends InstrumentItem {
        public final String modelTexture;

        TwoHanded(Kind kind, String modelTexture) {
            super(kind);
            this.modelTexture = modelTexture;
        }
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack old, ItemStack next, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(old, next);
    }

    static final class Keytar extends InstrumentItem implements ChargeableItem {
        private static final int CAPACITY = 36000;

        Keytar() { super(Kind.KEYTAR); }
        @Override public int capacity() { return CAPACITY; }
        @Override public boolean isBarVisible(ItemStack stack) { return true; }
        @Override public int getBarWidth(ItemStack stack) { return ChargeableItem.barWidth(stack); }
        @Override public int getBarColor(ItemStack stack) { return ChargeableItem.energy(stack) == 0 ? 0xE55843 : 0x45E0CB; }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            lines.add(KeytarFinish.of(stack).description().withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("wearable.energy_hint", ChargeableItem.energy(stack), CAPACITY)
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
