package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.Occluders;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** What stops a stage light's rays besides blocks' collision shapes: the Occluders (screens) in its level, as traced. */
public final class LightingScreens {
    private LightingScreens() {
    }

    static List<Panel> snapshot(Level level, boolean render) {
        List<Panel> panels = new ArrayList<>();
        for (Occluders.Occluder occluder : Occluders.in(level)) {
            Pose3dc pose = SableCompat.pose(level, occluder.getBlockPos(), render);
            panels.add(new Panel(occluder.getBlockPos(), occluder.occluderBox(), pose));
        }
        return List.copyOf(panels);
    }

    static final class Panel {
        private final BlockPos anchor;
        private final VoxelShape shape;
        private final Pose3dc pose;
        private final AABB bounds;

        /** {@code box} (in its level's block space) at {@code anchor}, moved by {@code pose} if on a sub-level. */
        Panel(BlockPos anchor, AABB box, Pose3dc pose) {
            this.anchor = anchor.immutable();
            this.pose = pose == null ? null : new Pose3d(pose);
            shape = Shapes.create(box);
            AABB local = shape.bounds();
            bounds = (this.pose == null ? local : SableCompat.transform(local, this.pose::transformPosition)).inflate(1e-6);
        }

        LightingRaycast.Hit clip(Vec3 from, Vec3 to) {
            if (Math.max(from.x, to.x) < bounds.minX || Math.min(from.x, to.x) > bounds.maxX
                    || Math.max(from.y, to.y) < bounds.minY || Math.min(from.y, to.y) > bounds.maxY
                    || Math.max(from.z, to.z) < bounds.minZ || Math.min(from.z, to.z) > bounds.maxZ) {
                return null;
            }
            BlockHitResult hit = shape.clip(pose == null ? from : pose.transformPositionInverse(from),
                    pose == null ? to : pose.transformPositionInverse(to), BlockPos.ZERO);
            if (hit == null) {
                return null;
            }
            Vec3 point = hit.getLocation();
            Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            if (pose != null) {
                point = pose.transformPosition(point);
                normal = pose.transformNormal(normal).normalize();
            }
            return new LightingRaycast.Hit(hit.withPosition(anchor), point, normal);
        }
    }
}
