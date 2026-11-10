package org.haruyq.createmediasystems.megaphone;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

public final class MegaphoneItem extends Item {
    public final boolean black;
    public MegaphoneItem(Properties properties,boolean black){super(properties.stacksTo(1));this.black=black;}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        var item=player.getItemInHand(hand);
        if(player instanceof ServerPlayer server) {
            if(player.isShiftKeyDown())MegaphoneData.play(item,level,!MegaphoneData.playing(item));
            else {int slot=hand==InteractionHand.MAIN_HAND?player.getInventory().selected:40;
                server.openMenu(new SimpleMenuProvider((id,inventory,p)->new MegaphoneMenu(id,inventory,slot),item.getHoverName()),buf->buf.writeVarInt(slot));}
        }
        return InteractionResultHolder.sidedSuccess(item,level.isClientSide);
    }
    @Override public void inventoryTick(ItemStack item,Level level,Entity entity,int slot,boolean selected) {
        if(!level.isClientSide && entity instanceof Player player) {
            boolean held=(player.getMainHandItem()==item || player.getOffhandItem()==item) && player.isAlive() && !player.isSpectator();
            if(!held && MegaphoneData.playing(item))MegaphoneData.play(item,level,false);
            // The server clock is absolute; battery settlement is batched to avoid equipment packets every tick.
            if(level.getGameTime()%20==0 || MegaphoneData.id(item).isEmpty())MegaphoneData.tick(item,level);
        }
    }
    @Override public boolean shouldCauseReequipAnimation(ItemStack old,ItemStack next,boolean slotChanged){return slotChanged || !ItemStack.isSameItem(old,next);}
    @Override public void appendHoverText(ItemStack item,TooltipContext context,List<Component> lines,TooltipFlag flag) {
        lines.add(Component.translatable("megaphone.hint").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("megaphone.character").withStyle(ChatFormatting.GRAY));
    }
}
