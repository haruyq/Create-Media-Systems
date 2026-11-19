package org.haruyq.createmediasystems.items.instrument;

import java.util.List;

import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.haruyq.createmediasystems.items.power.ChargeableItem;

import net.minecraft.ChatFormatting;
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
    private final Kind kind;

    InstrumentItem(Kind kind) {
        super(new Properties().stacksTo(1));
        this.kind = kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (kind == Kind.KEYTAR && hand != InteractionHand.MAIN_HAND || kind == Kind.MELODICA && WearableInstruments.ready(player)) {
            return InteractionResultHolder.pass(stack);
        }
        if (player instanceof ServerPlayer server) WearableInstruments.begin(server, hand, kind);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
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
