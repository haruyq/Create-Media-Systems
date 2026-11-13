package org.haruyq.createmediasystems.items.launcher;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.ContraptionSurface;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

/** A swept segment with the actual contact coordinate; vanilla's throwable helper loses that coordinate. */
public final class DiscBallistics {
    private DiscBallistics() {}

    static List<AABB> hitBoxes(Entity target) {
        AABB body = target.getBoundingBox();
        var head = target instanceof LivingEntity living ? DiscHitZones.headBox(living) : Optional.<AABB>empty();
        if (head.isEmpty()) return List.of(body);
        // The torso stops at the head so a broad body box cannot intercept an inset head.
        return List.of(new AABB(body.minX, body.minY, body.minZ, body.maxX,
                Math.max(body.minY, head.get().minY), body.maxZ), head.get());
    }

    static Optional<Vec3> contact(AABB zone, Vec3 start, Vec3 end) {
        AABB box = zone.inflate(.06);
        return box.contains(start) ? Optional.of(start) : box.clip(start, end);
    }

    public static HitResult trace(Entity disc,Vec3 start,Vec3 end,Predicate<Entity> canHit) {
        return trace(disc, start, end, canHit, ContraptionSurface.nearest(disc, start, end));
    }

    static HitResult trace(Entity disc, Vec3 start, Vec3 end, Predicate<Entity> canHit, @Nullable ContraptionSurface.Hit moving) {
        HitResult closest=disc.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,disc));
        if (closest instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                && SableCompat.containing(disc.level(), block.getBlockPos()) != null) {
            var at = SableCompat.hitInWorld(disc.level(), block);
            closest = new BlockHitResult(at, block.getDirection(), block.getBlockPos(), block.isInside());
        }
        if (moving != null && start.distanceToSqr(moving.location()) < start.distanceToSqr(closest.getLocation())) {
            closest = new MovingHit(moving);
        }
        Vec3 limit=closest.getLocation();double distance=start.distanceToSqr(limit);
        for(Entity target:disc.level().getEntities(disc,disc.getBoundingBox().expandTowards(end.subtract(start)).inflate(1),canHit)) {
            for (var zone : hitBoxes(target)) {
                var contact = contact(zone, start, limit);
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

    static final class MovingHit extends BlockHitResult {
        final ContraptionSurface.Hit surface;

        MovingHit(ContraptionSurface.Hit surface) {
            super(surface.location(), surface.local().getDirection(), surface.local().getBlockPos(), surface.local().isInside());
            this.surface = surface;
        }
    }
}
