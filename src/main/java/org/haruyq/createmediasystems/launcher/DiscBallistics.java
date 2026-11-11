package org.haruyq.createmediasystems.launcher;

import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A swept segment with the actual contact coordinate; vanilla's throwable helper loses that coordinate. */
public final class DiscBallistics {
    private static final double ASSIST_RANGE = 32;
    private static final double ASSIST_ANGLE = Math.toRadians(4);

    private DiscBallistics() {}

    /** Correct a near miss toward a reachable body while preserving a shot already on target. */
    static Vec3 assistAim(DiscProjectile disc, Player shooter, Vec3 forward, Predicate<Entity> canHit) {
        Vec3 origin = disc.position();
        Vec3 end = origin.add(forward.scale(ASSIST_RANGE));
        AABB search = new AABB(origin, end).inflate(ASSIST_RANGE * Math.sin(ASSIST_ANGLE));
        var targets = disc.level().getEntitiesOfClass(LivingEntity.class, search,
                target -> canHit.test(target) && canAssist(shooter, target));
        if (targets.isEmpty() || trace(disc, origin, end, canHit) instanceof EntityHitResult) return forward;

        Vec3 bestDirection = forward;
        double bestAlignment = Math.cos(ASSIST_ANGLE);
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity target : targets) {
            Vec3 center = target.getBoundingBox().getCenter();
            Vec3 offset = center.subtract(origin);
            double distance = offset.lengthSqr();
            if (distance < 1e-6 || distance > ASSIST_RANGE * ASSIST_RANGE) continue;
            Vec3 direction = offset.scale(1 / Math.sqrt(distance));
            double alignment = forward.dot(direction);
            if (alignment < bestAlignment || (alignment == bestAlignment && distance >= bestDistance)) continue;
            if (trace(disc, origin, center, canHit) instanceof EntityHitResult hit && hit.getEntity() == target) {
                bestDirection = direction;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }
        return bestDirection;
    }

    private static boolean canAssist(Player shooter, LivingEntity target) {
        if (target == shooter || !target.isAlive() || !target.isAttackable() || target.isInvulnerable()
                || target.isInvisibleTo(shooter) || shooter.isAlliedTo(target)) {
            return false;
        }
        if (target instanceof OwnableEntity owned && shooter.getUUID().equals(owned.getOwnerUUID())) return false;
        return !(target instanceof Player player) || (!player.getAbilities().invulnerable && shooter.canHarmPlayer(player));
    }

    public static HitResult trace(Entity disc,Vec3 start,Vec3 end,Predicate<Entity> canHit) {
        HitResult closest=disc.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,disc));
        Vec3 limit=closest.getLocation();double distance=start.distanceToSqr(limit);
        for(Entity target:disc.level().getEntities(disc,disc.getBoundingBox().expandTowards(end.subtract(start)).inflate(1),canHit)) {
            var body=target.getBoundingBox();
            var head=target instanceof LivingEntity living?DiscHitZones.headBox(living):java.util.Optional.<AABB>empty();
            var zones=new java.util.ArrayList<AABB>(2);
            if(head.isPresent()) {
                // A broad chest AABB must not intercept a shot before a narrower, inset head.
                zones.add(new AABB(body.minX,body.minY,body.minZ,body.maxX,Math.max(body.minY,head.get().minY),body.maxZ));
                zones.add(head.get());
            } else zones.add(body);
            for(var zone:zones) {
                var box=zone.inflate(.06);
                var contact=box.contains(start)?java.util.Optional.of(start):box.clip(start,limit);
                if(contact.isPresent()) {
                    double d=start.distanceToSqr(contact.get());
                    boolean headTie=target instanceof net.minecraft.world.entity.boss.EnderDragonPart headPart && headPart==headPart.parentMob.head
                            && closest instanceof EntityHitResult previous && previous.getEntity() instanceof net.minecraft.world.entity.boss.EnderDragonPart other
                            && other.parentMob==headPart.parentMob && Math.abs(d-distance)<1e-7;
                    if(d<distance || headTie || closest.getType()==HitResult.Type.MISS && d<=distance) {
                        closest=new EntityHitResult(target,contact.get());distance=d;
                    }
                }
            }
        }
        return closest;
    }
}
