package org.haruyq.createmediasystems;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Block hits on a loaded Create contraption, whose shapes live in its own block coordinates. */
public final class ContraptionSurface {
    private ContraptionSurface() {}

    public record Hit(AbstractContraptionEntity carrier, BlockHitResult local, Vec3 location) {
        public Vec3 outside(double offset) {
            return SableCompat.toWorld(carrier.level(), carrier.blockPosition(),
                    carrier.toGlobalVector(local.getLocation().relative(local.getDirection(), offset), 1));
        }
    }

    @Nullable
    public static Hit nearest(Entity actor, Vec3 start, Vec3 end) {
        Hit nearest = null;
        double limit = start.distanceToSqr(end);
        var level = actor.level();
        for (var carrier : carriers(level, new AABB(start, end).inflate(1))) {
            if (!carrier.isAlive() || carrier.getContraption() == null) continue;
            var anchor = carrier.blockPosition();
            Vec3 from = carrier.toLocalVector(SableCompat.toBlockSpace(level, anchor, start), 1);
            Vec3 to = carrier.toLocalVector(SableCompat.toBlockSpace(level, anchor, end), 1);
            var hit = carrier.getContraption().getContraptionWorld()
                    .clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            Vec3 at = SableCompat.toWorld(level, anchor, carrier.toGlobalVector(hit.getLocation(), 1));
            double distance = start.distanceToSqr(at);
            if (distance < limit) {
                limit = distance;
                nearest = new Hit(carrier, hit, at);
            }
        }
        return nearest;
    }

    public static List<AbstractContraptionEntity> carriers(Level level, AABB bounds) {
        List<AbstractContraptionEntity> nearby = level.getEntitiesOfClass(AbstractContraptionEntity.class, bounds);
        List<AbstractContraptionEntity> inPlots = null;
        for (var ship : SableCompat.subLevels(level, bounds)) {
            AABB plot = SableCompat.transform(bounds, ship.logicalPose()::transformPositionInverse);
            var found = level.getEntitiesOfClass(AbstractContraptionEntity.class, plot);
            if (!found.isEmpty()) {
                if (inPlots == null) inPlots = new ArrayList<>(nearby);
                inPlots.addAll(found);
            }
        }
        return inPlots != null ? inPlots : nearby;
    }

    /** The surface motion shared by a trolley and its puller, before either's own walking step. */
    public static Vec3 sharedStep(Entity trolley, Entity puller) {
        var shipStep = SableCompat.sharedStep(trolley, puller);
        var level = trolley.level();
        for (var carrier : carriers(level, trolley.getBoundingBox().inflate(1))) {
            if (carrier.collidingEntities.containsKey(trolley)
                    && (carrier.collidingEntities.containsKey(puller) || puller.getVehicle() == carrier)) {
                var anchor = carrier.blockPosition();
                Vec3 at = SableCompat.toBlockSpace(level, anchor, puller.position());
                Vec3 moved = SableCompat.toWorld(level, anchor, at.add(carrier.getContactPointMotion(at)));
                Vec3 step = moved.subtract(puller.position());
                return shipStep == null ? step : step.add(shipStep);
            }
        }
        return shipStep == null ? Vec3.ZERO : shipStep;
    }
}
