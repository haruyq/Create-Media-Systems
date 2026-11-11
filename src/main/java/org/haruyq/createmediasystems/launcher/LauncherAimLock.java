package org.haruyq.createmediasystems.launcher;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

final class LauncherAimLock {
    private ItemStack weapon = ItemStack.EMPTY;
    @Nullable private LivingEntity target;
    private LauncherFeedback.Lock displayed = LauncherFeedback.Lock.NONE;

    static void tick(Player shooter) {
        ItemStack held = shooter.getMainHandItem();
        if (!active(shooter, held)) {
            LauncherAimLock old = shooter.removeData(LauncherFeature.AIM_LOCK);
            if (old != null) old.show(shooter, LauncherFeedback.Lock.NONE);
        } else if (shooter.tickCount % 3 == 0) {
            direction(shooter, held, shooter.getLookAngle());
        }
    }

    static Vec3 direction(Player shooter, ItemStack stack, Vec3 forward) {
        if (!active(shooter, stack)) return forward;
        LauncherAimLock lock = shooter.getData(LauncherFeature.AIM_LOCK);
        var solution = DiscAimAssist.select(shooter, forward, lock.weapon == stack ? lock.target : null);
        lock.weapon = stack;
        lock.target = DiscAimAssist.parent(solution.target());
        lock.show(shooter, feedback(lock.target, solution.target()));
        return solution.direction();
    }

    private static boolean active(Player shooter, ItemStack weapon) {
        return shooter.isAlive() && !shooter.isSpectator() && DiscLauncherItem.hasAimModule(weapon)
                && DiscLauncherItem.aiming(shooter, weapon) && !LauncherMagazine.reloading(weapon);
    }

    private static LauncherFeedback.Lock feedback(@Nullable LivingEntity target, @Nullable Entity part) {
        if (target == null) return LauncherFeedback.Lock.NONE;
        if (part instanceof EnderDragonPart dragonPart) {
            var parts = dragonPart.parentMob.getSubEntities();
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] == part) return new LauncherFeedback.Lock(target.getId(), i);
            }
        }
        return new LauncherFeedback.Lock(target.getId(), -1);
    }

    private void show(Player shooter, LauncherFeedback.Lock next) {
        if (!next.equals(displayed) && shooter instanceof ServerPlayer server) {
            PacketDistributor.sendToPlayer(server, next);
        }
        displayed = next;
    }
}
