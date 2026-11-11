package org.haruyq.createmediasystems.launcher;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
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

    record Focus(Entity part, int zone) {}
    record Solution(Focus focus, Vec3 point) {}
    private record Aim(Player shooter, Vec3 origin, Vec3 forward, double range, double drag, int inputDelay,
                       Predicate<Entity> canHit) {
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

    @Nullable
    static Solution select(Player shooter, @Nullable Focus locked, int inputDelay) {
        boolean submerged = shooter.isEyeInFluid(FluidTags.WATER);
        // The disc travels in a straight line and uses vanilla throwable drag at each step.
        Aim aim = new Aim(shooter, shooter.getEyePosition(), shooter.getLookAngle(),
                submerged ? 24 : 64, submerged ? .8f : .99f, inputDelay,
                entity -> entity.canBeHitByProjectile() && !shooter.isPassengerOfSameVehicle(entity));
        if (locked != null && eligible(shooter, parent(locked.part)) && aim.canHit.test(locked.part)) {
            List<AABB> boxes = DiscBallistics.hitBoxes(locked.part);
            if (locked.zone < boxes.size()) {
                AABB box = boxes.get(locked.zone);
                Solution retained = aimAt(aim, locked, box);
                if (retained != null && Math.max(alignment(aim, box.getCenter()), alignment(aim, retained.point))
                        >= RETAIN_ALIGNMENT) return retained;
            }
        }

        Vec3 end = aim.origin.add(aim.forward.scale(aim.range));
        if (aim.trace(end) instanceof EntityHitResult direct) {
            Entity target = direct.getEntity();
            if (!eligible(shooter, parent(target))) return null;
            int zone = hitZone(direct);
            Solution solution = aimAt(aim, new Focus(target, zone), DiscBallistics.hitBoxes(target).get(zone));
            if (solution != null) return solution;
        }

        AABB search = new AABB(aim.origin, end).inflate(aim.range * Math.sin(ACQUIRE_ANGLE));
        var candidates = shooter.level().getEntities(shooter, search, entity -> aim.canHit.test(entity)
                && eligible(shooter, parent(entity)));
        return choose(aim, candidates);
    }

    @Nullable
    private static Solution choose(Aim aim, List<Entity> candidates) {
        Solution best = null;
        double bestAlignment = ACQUIRE_ALIGNMENT;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity target : candidates) {
            var boxes = DiscBallistics.hitBoxes(target);
            for (int zone = 0; zone < boxes.size(); zone++) {
                AABB box = boxes.get(zone);
                Vec3 center = box.getCenter();
                double distance = center.distanceToSqr(aim.origin);
                double alignment = alignment(aim, center);
                if (alignment < bestAlignment || alignment == bestAlignment && distance >= bestDistance) continue;
                Solution solution = aimAt(aim, new Focus(target, zone), box);
                if (solution == null) continue;
                best = solution;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double alignment(Aim aim, Vec3 point) {
        return aim.forward.dot(point.subtract(aim.origin).normalize());
    }

    private static int hitZone(EntityHitResult hit) {
        return hit.getEntity() instanceof LivingEntity living && DiscHitZones.headshot(living, hit.getLocation()) ? 1 : 0;
    }

    @Nullable
    private static Solution aimAt(Aim aim, Focus focus, AABB box) {
        Entity target = focus.part;
        Vec3 center = box.getCenter();
        if (center.distanceToSqr(aim.origin) > aim.range * aim.range
                || !(aim.trace(center) instanceof EntityHitResult visible)
                || visible.getEntity() != target || hitZone(visible) != focus.zone) return null;
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
        // Advance the target while the steering sample travels to the client and the trigger travels back.
        for (int tick = 0; tick < aim.inputDelay; tick++) {
            if (turn != 0) motion = motion.yRot(turn);
            shift = shift.add(motion);
        }
        while (travelled < aim.range) {
            double reach = Math.min(aim.range, travelled + speed);
            if (turn != 0) motion = motion.yRot(turn);
            shift = shift.add(motion);
            Vec3 origin = aim.origin.subtract(shift);
            Vec3 direction = center.subtract(origin).normalize();
            Vec3 start = origin.add(direction.scale(travelled));
            Vec3 end = origin.add(direction.scale(reach));
            var contact = DiscBallistics.contact(box, start, end);
            if (contact.isPresent() && aim.forward.dot(direction) >= LEAD_ALIGNMENT
                    && aim.clearTo(target, contact.get().add(shift))) {
                return new Solution(focus, center.add(shift));
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
