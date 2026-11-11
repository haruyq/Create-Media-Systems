package org.haruyq.createmediasystems.launcher;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

final class LauncherAimLock {
    private ItemStack weapon = ItemStack.EMPTY;
    @Nullable private DiscAimAssist.Focus focus;
    private LauncherFeedback.Lock displayed = LauncherFeedback.Lock.NONE;

    static LauncherFeedback.Lock tick(Player shooter) {
        ItemStack held = shooter.getMainHandItem();
        if (!active(shooter, held)) {
            clear(shooter);
            return LauncherFeedback.Lock.NONE;
        }
        LauncherAimLock lock = shooter.getData(LauncherFeature.AIM_LOCK);
        if (lock.weapon == held && lock.focus == null && shooter.tickCount % 3 != 0) return lock.displayed;
        int inputDelay = shooter instanceof ServerPlayer server && server.connection != null
                ? 1 + Math.clamp(Math.round(server.connection.latency() / 50f), 0, 6) : 0;
        var solution = DiscAimAssist.select(shooter, lock.weapon == held ? lock.focus : null, inputDelay);
        lock.weapon = held;
        lock.focus = solution == null ? null : solution.focus();
        lock.show(shooter, feedback(solution));
        return lock.displayed;
    }

    static void clear(Player shooter) {
        LauncherAimLock old = shooter.removeData(LauncherFeature.AIM_LOCK);
        if (old != null) old.show(shooter, LauncherFeedback.Lock.NONE);
    }

    private static boolean active(Player shooter, ItemStack weapon) {
        return shooter.isAlive() && !shooter.isSpectator() && DiscLauncherItem.hasAimModule(weapon)
                && DiscLauncherItem.aiming(shooter, weapon) && !LauncherMagazine.reloading(weapon);
    }

    private static LauncherFeedback.Lock feedback(@Nullable DiscAimAssist.Solution solution) {
        if (solution == null) return LauncherFeedback.Lock.NONE;
        Entity part = solution.focus().part();
        if (part instanceof EnderDragonPart dragonPart) {
            var parts = dragonPart.parentMob.getSubEntities();
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] == part) return new LauncherFeedback.Lock(dragonPart.parentMob.getId(), i, solution.point());
            }
        }
        return new LauncherFeedback.Lock(part.getId(), -1, solution.point());
    }

    private void show(Player shooter, LauncherFeedback.Lock next) {
        if (!next.equals(displayed) && shooter instanceof ServerPlayer server && server.connection != null) {
            PacketDistributor.sendToPlayer(server, next);
        }
        displayed = next;
    }
}
