package org.haruyq.createmediasystems.launcher;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class DiscAimAssist {
    private static final double ACQUIRE_ANGLE = Math.toRadians(6);
    private static final double ACQUIRE_ALIGNMENT = Math.cos(ACQUIRE_ANGLE);
    private static final double RETAIN_ALIGNMENT = Math.cos(Math.toRadians(24));
    private static final double LEAD_ALIGNMENT = Math.cos(Math.toRadians(30));

    record Solution(Vec3 direction, @Nullable Entity target) {}
    private record Intercept(Vec3 direction, Vec3 point) {}
    private record Aim(Player shooter, Vec3 origin, Vec3 forward, double range, double drag, Predicate<Entity> canHit) {
        HitResult trace(Vec3 point) {
            return DiscBallistics.trace(shooter, origin, point, canHit);
        }

        boolean clearTo(Entity target, Vec3 point) {
            LivingEntity living = parent(target);
            return DiscBallistics.trace(shooter, origin, point, other -> canHit.test(other) && parent(other) != living)
                    .getType() == HitResult.Type.MISS;
        }
    }

    private DiscAimAssist() {}

    static Solution select(Player shooter, Vec3 forward, @Nullable LivingEntity locked) {
        boolean submerged = shooter.isEyeInFluid(FluidTags.WATER);
        // The disc travels in a straight line and uses vanilla throwable drag at each step.
        Aim aim = new Aim(shooter, shooter.getEyePosition(), forward.normalize(),
                submerged ? 24 : 64, submerged ? .8f : .99f,
                entity -> entity.canBeHitByProjectile() && !shooter.isPassengerOfSameVehicle(entity));
        if (eligible(shooter, locked)) {
            List<? extends Entity> parts = locked instanceof EnderDragon dragon
                    ? List.of(dragon.getSubEntities()) : List.of(locked);
            Solution retained = choose(aim, parts, RETAIN_ALIGNMENT);
            if (retained != null) return retained;
        }

        Vec3 end = aim.origin.add(aim.forward.scale(aim.range));
        if (aim.trace(end) instanceof EntityHitResult direct) {
            Entity target = direct.getEntity();
            if (!eligible(shooter, parent(target))) return new Solution(forward, null);
            Solution solution = aimAt(aim, target);
            if (solution != null) return solution;
        }

        AABB search = new AABB(aim.origin, end).inflate(aim.range * Math.sin(ACQUIRE_ANGLE));
        var candidates = shooter.level().getEntities(shooter, search, entity -> aim.canHit.test(entity)
                && eligible(shooter, parent(entity)));
        Solution acquired = choose(aim, candidates, ACQUIRE_ALIGNMENT);
        return acquired != null ? acquired : new Solution(forward, null);
    }

    @Nullable
    private static Solution choose(Aim aim, List<? extends Entity> candidates, double minimumAlignment) {
        Solution best = null;
        double bestAlignment = minimumAlignment;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity target : candidates) {
            if (!aim.canHit.test(target)) continue;
            Vec3 center = DiscBallistics.hitBoxes(target).getFirst().getCenter();
            Vec3 offset = center.subtract(aim.origin);
            double distance = offset.lengthSqr();
            if (distance < 1e-6 || distance > aim.range * aim.range) continue;
            double alignment = aim.forward.dot(offset.scale(1 / Math.sqrt(distance)));
            if (alignment < minimumAlignment) continue;
            if (!(aim.trace(center) instanceof EntityHitResult visible) || visible.getEntity() != target) continue;

            Solution solution = aimAt(aim, target);
            if (solution == null) continue;
            if (solution.direction.equals(aim.forward)) return solution;
            if (alignment > bestAlignment || alignment == bestAlignment && distance < bestDistance) {
                best = solution;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Nullable
    private static Solution aimAt(Aim aim, Entity target) {
        Intercept direct = intercept(aim, target, aim.forward);
        if (direct != null && aim.clearTo(target, direct.point)) return new Solution(aim.forward, target);
        Intercept lead = intercept(aim, target, null);
        return lead != null && aim.forward.dot(lead.direction) >= LEAD_ALIGNMENT && aim.clearTo(target, lead.point)
                ? new Solution(lead.direction, target) : null;
    }

    @Nullable
    private static Intercept intercept(Aim aim, Entity target, @Nullable Vec3 fixedDirection) {
        var boxes = DiscBallistics.hitBoxes(target);
        Vec3 center = boxes.getFirst().getCenter();
        // The dragon updates each part's old position separately, including when its body turns.
        Vec3 motion = target instanceof Player ? target.getKnownMovement()
                : target.position().subtract(target.xo, target.yo, target.zo);
        if (!(motion.lengthSqr() <= 9)) motion = Vec3.ZERO;
        float turn = target instanceof EnderDragonPart part
                ? -Mth.DEG_TO_RAD * Math.clamp(Mth.wrapDegrees(part.parentMob.getYRot() - part.parentMob.yRotO), -6, 6)
                : 0;

        double travelled = 0;
        double speed = DiscProjectile.LAUNCH_SPEED;
        Vec3 shift = Vec3.ZERO;
        while (travelled < aim.range) {
            double reach = Math.min(aim.range, travelled + speed);
            if (turn != 0) motion = motion.yRot(turn);
            shift = shift.add(motion);
            Vec3 origin = aim.origin.subtract(shift);
            Vec3 direction = fixedDirection != null ? fixedDirection : center.subtract(origin).normalize();
            Vec3 start = origin.add(direction.scale(travelled));
            Vec3 end = origin.add(direction.scale(reach));
            for (AABB box : boxes) {
                var contact = DiscBallistics.contact(box, start, end);
                if (contact.isPresent()) return new Intercept(direction, contact.get().add(shift));
            }
            travelled = reach;
            speed *= aim.drag;
        }
        return null;
    }

    @Nullable
    static LivingEntity parent(@Nullable Entity entity) {
        if (entity instanceof EnderDragonPart part) return part.parentMob;
        return entity instanceof LivingEntity living ? living : null;
    }

    private static boolean eligible(Player shooter, @Nullable LivingEntity target) {
        if (target == null || target == shooter || target.level() != shooter.level() || !target.isAlive()
                || !target.isAttackable() || target.isInvulnerable() || target.isInvisibleTo(shooter)
                || shooter.isAlliedTo(target)) {
            return false;
        }
        if (target instanceof OwnableEntity owned && shooter.getUUID().equals(owned.getOwnerUUID())) return false;
        return !(target instanceof Player player) || (!player.getAbilities().invulnerable && shooter.canHarmPlayer(player));
    }
}
