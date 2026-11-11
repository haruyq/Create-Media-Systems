package org.haruyq.createmediasystems.lighting;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.SourceLinks;
import org.haruyq.createmediasystems.stream.VideoSource;

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
        BlockPos pos = target.pos();
        if (!player.level().isClientSide) {
            UUID id = player.getMainHandItem().get(LightingFeature.LIGHTING_SOURCE.get());
            LightingConsoleBlock.Entity legacy = id == null ? LightingConsoleBlock.find(player.level(), pos) : null;
            if (legacy != null) {
                id = legacy.identity();
                if (id != null) {
                    player.getMainHandItem().set(LightingFeature.LIGHTING_SOURCE.get(), id);
                }
            }
            pos = SourceLinks.resolve(player.level(), id, pos);
            if (pos != null && !pos.equals(target.pos())) {
                player.getMainHandItem().set(LightingFeature.LIGHTING_TARGET.get(), GlobalPos.of(target.dimension(), pos));
            }
        }
        return pos == null ? null : LightingConsoleBlock.find(player.level(), pos);
    }

    public static void link(Player player, BlockPos pos) {
        Level level = player.level();
        if (!player.getMainHandItem().is(LightingFeature.LIGHTING_LINK.get()) || !player.mayBuild()
                || !level.isLoaded(pos) || !SableCompat.mayInteract(level, player, pos)
                || player.distanceToSqr(SableCompat.toWorld(level, pos.getCenter())) > Reach.QUILL * Reach.QUILL) {
            return;
        }
        var block = level.getBlockEntity(pos);
        if (block instanceof LightingConsoleBlock.Entity part && !part.formed) {
            part.updateStructure();
        }
        LightingConsoleBlock.Entity clicked = LightingConsoleBlock.find(level, pos);
        if (clicked != null) {
            player.getMainHandItem().set(LightingFeature.LIGHTING_TARGET.get(),
                    GlobalPos.of(level.dimension(), clicked.getBlockPos()));
            player.getMainHandItem().set(LightingFeature.LIGHTING_SOURCE.get(), clicked.identity());
            clicked.refreshLinks();
            say(player, "picked", LightingText.group(clicked.settings, clicked.settings.selectedGroup));
            return;
        }
        if (block instanceof LightingConsoleBlock.Entity || level.getBlockState(pos).getBlock() instanceof LightingMonitorBlock) {
            say(player, "assembly");
            return;
        }
        LightingConsoleBlock.Entity console = selected(player);
        if (console == null) {
            say(player, "pick_first");
            return;
        }
        if (SableCompat.distance(level, console.getBlockPos(), pos) > SourceLinks.MAX_LINK_DISTANCE
                || !SableCompat.mayInteract(level, player, console.getBlockPos())) {
            say(player, "too_far");
            return;
        }
        if (block instanceof StageLightBlock.Entity light) {
            LightingConsoleBlock.Entity previous = light.linkedController();
            if (previous != null && !SableCompat.mayInteract(level, player, previous.getBlockPos())) {
                return;
            }
            if (player.isShiftKeyDown() || previous == console && light.group == console.settings.selectedGroup) {
                light.unlink();
                say(player, "link_removed");
            } else if (console.bind(light, console.settings.selectedGroup)) {
                say(player, "linked", LightingText.group(console.settings, light.group), LightingText.number(light.order + 1));
            } else if (!console.accepts(light, console.settings.selectedGroup)) {
                int group = console.settings.selectedGroup;
                say(player, "other_family", LightingText.group(console.settings, group),
                        StageLightBlock.familyName(console.family(group)));
            } else {
                say(player, "full");
            }
        } else if (block instanceof MediaSource) {
            if (block instanceof VideoSource) {
                console.setVideoSource(pos);
            }
            if (!player.isShiftKeyDown() || !(block instanceof VideoSource)) {
                console.setAudioSource(pos);
            }
            console.hasAudio = false;
            console.hasVideo = false;
            console.changed();
            say(player, "source_linked");
        }
    }

    private static void say(Player player, String key, Object... args) {
        player.displayClientMessage(LightingText.message(key, args), true);
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
                        < SourceLinks.MAX_LINK_DISTANCE * SourceLinks.MAX_LINK_DISTANCE) {
            LightingSettings.Group group = console.settings.groups[console.settings.selectedGroup];
            group.target = target.getUUID();
            group.motion = LightingSettings.Group.MOTION_TRACK;
            console.changed();
            say(player, "following", target.getName().copy());
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
        tooltip.add(LightingText.message("link_help"));
        tooltip.add(LightingText.message("track_help"));
    }
}
