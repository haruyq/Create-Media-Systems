package org.haruyq.createmediasystems.partybox;

import java.util.List;
import org.haruyq.createmediasystems.CreateMediaSystems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

public final class PartyBoxItem extends Item {
    public PartyBoxItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var box = new PartyBoxEntity(CreateMediaSystems.PARTYBOX_ENTITY.get(), level);
        var at = context.getClickLocation().relative(context.getClickedFace(), .02);
        box.setPos(at.x, at.y, at.z);
        box.setYRot(context.getPlayer() == null ? 0 : context.getPlayer().getYRot());
        box.loadPortable(context.getItemInHand().get(CreateMediaSystems.PARTYBOX_STATE.get()));
        if (!level.noCollision(box) || !level.addFreshEntity(box)) return InteractionResult.FAIL;
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> text, TooltipFlag flag) {
        text.add(Component.literal("240 W  |  68 Wh  |  16.5 kg").withStyle(ChatFormatting.GRAY));
        text.add(Component.translatable("partybox.controls").withStyle(ChatFormatting.AQUA));
        text.add(Component.translatable("partybox.charge_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
