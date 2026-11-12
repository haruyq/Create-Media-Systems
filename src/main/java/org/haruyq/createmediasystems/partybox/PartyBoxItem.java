package org.haruyq.createmediasystems.partybox;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class PartyBoxItem extends Item {
    public PartyBoxItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var at = SableCompat.hitInBlockSpace(level, context.getClickedPos(), context.getClickLocation())
                .relative(context.getClickedFace(), .02);
        return place(level, context.getPlayer(), context.getItemInHand(),
                SableCompat.toWorld(level, context.getClickedPos(), at));
    }

    static InteractionResult place(Level level, @Nullable Player player, ItemStack item, Vec3 at) {
        var box = new PartyBoxEntity(PartyBoxFeature.PARTYBOX_ENTITY.get(), level);
        box.setPos(at.x, at.y, at.z);
        box.setYRot(player == null ? 0 : player.getYRot());
        box.loadPortable(item.get(PartyBoxFeature.PARTYBOX_STATE.get()));
        if (!level.noCollision(box) || !level.addFreshEntity(box)) return InteractionResult.FAIL;
        if (player == null || !player.getAbilities().instabuild) item.shrink(1);
        return InteractionResult.CONSUME;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> text, TooltipFlag flag) {
        text.add(Component.literal("240 W  |  68 Wh  |  16.5 kg").withStyle(ChatFormatting.GRAY));
        text.add(Component.translatable("partybox.controls").withStyle(ChatFormatting.AQUA));
        text.add(Component.translatable("partybox.remote_hint").withStyle(ChatFormatting.GRAY));
        text.add(Component.translatable("partybox.charge_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
