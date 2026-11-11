package org.haruyq.createmediasystems.launcher;

import java.util.List;

import org.haruyq.createmediasystems.stream.MediaItem;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Rechargeable-cell launcher with an independently animated breech and support hand. */
public final class DiscLauncherItem extends Item {
    public static final int FIRE_INTERVAL_TICKS = 4;

    public DiscLauncherItem(Properties properties) { super(properties); }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack previous, ItemStack next, boolean slotChanged) {
        // Energy, escrow and animation timestamps change the stack without changing the held weapon.
        return slotChanged || previous.getItem() != next.getItem();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
        if(LauncherMagazine.reloading(stack))return InteractionResultHolder.fail(stack);
        player.startUsingItem(hand);
        player.setSprinting(false);
        return InteractionResultHolder.consume(stack);
    }

    @Override public net.minecraft.world.item.UseAnim getUseAnimation(ItemStack stack){return net.minecraft.world.item.UseAnim.NONE;}
    @Override public int getUseDuration(ItemStack stack,net.minecraft.world.entity.LivingEntity user){return 72000;}
    @Override public boolean canAttackBlock(net.minecraft.world.level.block.state.BlockState state,Level level,net.minecraft.core.BlockPos pos,Player player){return false;}
    @Override public boolean onLeftClickEntity(ItemStack stack,Player player,Entity entity){return true;}

    /** Only server trigger requests enter this method. Right-click can never spend energy or fire. */
    public static boolean fire(Player player,float yaw,float pitch) {
        Level level=player.level();ItemStack stack=player.getMainHandItem();
        if(level.isClientSide || !player.isAlive() || player.isSpectator() || !(stack.getItem() instanceof DiscLauncherItem)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch)>90)return false;
        long now = level.getGameTime();
        Long previous=stack.get(LauncherFeature.LAUNCHER_SHOT_AT.get());
        if (LauncherMagazine.reloading(stack) || player.getCooldowns().isOnCooldown(stack.getItem())
                || previous!=null && now>=previous && now-previous<FIRE_INTERVAL_TICKS)return false;
        if (LauncherMagazine.count(stack) == 0) {
            boolean started = LauncherMagazine.reserve(player, stack, now,true);
            if (started) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        LauncherFeature.DISC_LATCH_SOUND.get(), SoundSource.PLAYERS, .6f, 1.2f);
            } else if (!LauncherMagazine.reloading(stack) && now%20==0) player.displayClientMessage(
                    Component.translatable("cell.create_media_systems.need_cell"), true);
            return false;
        }
        ItemStack before = LauncherMagazine.cell(stack).copy();
        if (!DiscCellItem.hasMaster(before)) {
            player.displayClientMessage(Component.translatable("cell.create_media_systems.empty"), true);
            return false;
        }
        if (!MediaItem.allowed(DiscCellItem.master(before))) {
            player.displayClientMessage(Component.translatable("create_media_systems.url.rejected"), true);
            return false;
        }
        DiscCellTier tier = LauncherMagazine.tier(stack);
        ItemStack disc = LauncherMagazine.pop(stack);
        if (disc.isEmpty()) return false;
        DiscProjectile projectile = new DiscProjectile(level, player, disc);
        projectile.setCellTier(tier);
        projectile.setPos(player.getEyePosition());
        var direction=net.minecraft.world.phys.Vec3.directionFromRotation(pitch,yaw);
        boolean aimed=player.isUsingItem() && player.getUseItem().is(stack.getItem());
        // Eye-line origin and no inherited strafe velocity: the reticle is the shot's actual direction.
        projectile.shoot(direction.x,direction.y,direction.z,5.2f,aimed?0f:.15f);
        if (!level.addFreshEntity(projectile)) {
            LauncherMagazine.setCell(stack, before);
            return false;
        }
        stack.set(LauncherFeature.LAUNCHER_SHOT_AT.get(), now);
        player.getCooldowns().addCooldown(stack.getItem(), FIRE_INTERVAL_TICKS);
        player.setSprinting(false);
        if(player instanceof net.minecraft.server.level.ServerPlayer server)
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(server,new LauncherFeedback.Shot(now));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                LauncherFeature.DISC_FIRE_SOUND.get(), SoundSource.PLAYERS, 0.85f,
                1.15f + (level.random.nextFloat() * .15f));
        return true;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof Player player)) return;
        // The one place a carried launcher passes through: a pre-cell launcher refunds its discs on its first tick.
        LauncherMagazine.migrateLegacy(player, stack);
        Long started = stack.get(LauncherFeature.LAUNCHER_RELOAD_START.get());
        if (started == null) return;
        long age = level.getGameTime() - started;
        // A world-time jump can only make the already-escrowed reload finish sooner.
        if (age >= LauncherTimeline.RELOAD_TICKS || age < -20) {
            LauncherMagazine.finish(player, stack);
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    LauncherFeature.DISC_LATCH_SOUND.get(), SoundSource.PLAYERS, 0.65f, 1.4f);
        } else if (isSelected && LauncherTimeline.Cue.at(age) instanceof LauncherTimeline.Cue cue) {
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    cue == LauncherTimeline.Cue.CHARGE ? LauncherFeature.DISC_CHARGE_SOUND.get() : LauncherFeature.DISC_INSERT_SOUND.get(),
                    SoundSource.PLAYERS, 0.65f, cue.pitch);
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) { return true; }
    @Override
    public int getBarWidth(ItemStack stack) {
        ItemStack cell = LauncherMagazine.cell(stack);
        return cell.getItem() instanceof DiscCellItem item ? item.getBarWidth(cell) : 0;
    }
    @Override
    public int getBarColor(ItemStack stack) { return LauncherMagazine.reloading(stack) ? 0xE7AF43 : LauncherMagazine.tier(stack).color; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        DiscCellTier tier = LauncherMagazine.tier(stack);
        tooltip.add(LauncherMagazine.cell(stack).isEmpty() ? Component.translatable("cell.create_media_systems.no_cell")
                : Component.translatable("cell.create_media_systems.stats", tier.level, LauncherMagazine.count(stack),
                    tier.shots, (int) tier.damage).withStyle(ChatFormatting.GOLD));
        if (LauncherMagazine.reloading(stack)) {
            tooltip.add(Component.translatable("create_media_systems.launcher.reloading").withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("create_media_systems.launcher.controls").withStyle(ChatFormatting.GRAY));
    }
}
