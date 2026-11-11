package org.haruyq.createmediasystems.lighting;

import java.util.List;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.Link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class LightingLinkItem extends Item {
    public LightingLinkItem(Properties properties) {
        super(properties);
    }

    public static LightingConsoleBlock.Entity selected(Player player) {
        GlobalPos target = player.getMainHandItem().get(LightingFeature.LIGHTING_TARGET.get());
        if (target == null || !target.dimension().equals(player.level().dimension())) {
            return null;
        }
        BlockPos pos = Link.follow(player.level(), player.getMainHandItem(), LightingFeature.LIGHTING_TARGET.get(),
                LightingFeature.LIGHTING_SOURCE.get());
        return pos == null ? null : LightingConsoleBlock.find(player.level(), pos);
    }

    /** Does what {@code player}'s right-click on {@code pos} does (see LinkAction), on the server. */
    public static void link(Player player, BlockPos pos) {
        Level level = player.level();
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof LightingConsoleBlock.Entity part && !part.formed) {
            part.updateStructure();
        }
        LinkAction.resolve(player, pos).perform(player);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() != null && !context.getLevel().isClientSide) {
            link(context.getPlayer(), context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Player)) {
            return InteractionResult.PASS;
        }
        if (!player.level().isClientSide) {
            follow(player, target);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    private static void follow(Player player, LivingEntity target) {
        LightingConsoleBlock.Entity console = selected(player);
        if (console != null && player.mayBuild() && SableCompat.mayInteract(player.level(), player, console.getBlockPos())
                && player.distanceToSqr(SableCompat.toWorld(player.level(), console.getBlockPos().getCenter()))
                        < Link.MAX_DISTANCE * Link.MAX_DISTANCE) {
            LightingSettings.Group group = console.settings.groups[console.settings.selectedGroup];
            group.target = target.getUUID();
            group.motion = LightingSettings.Motion.TRACK;
            console.changed();
            player.displayClientMessage(LightingText.message(LightingText.ON, "following", target.getName().copy()), true);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player.isShiftKeyDown()) {
            follow(player, player);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(LightingText.message(LightingText.MUTED, "link_help"));
        tooltip.add(LightingText.message(LightingText.MUTED, "track_help"));
    }
}
