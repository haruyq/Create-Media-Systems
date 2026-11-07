package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.display.ImageViewerBlock;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class LightingScreens {
    private static final WeakHashMap<DisplayBlock.Entity, Boolean> SCREENS = new WeakHashMap<>();

    private LightingScreens() {
    }

    public static synchronized void add(DisplayBlock.Entity display) {
        SCREENS.put(display, Boolean.TRUE);
    }

    public static synchronized void remove(DisplayBlock.Entity display) {
        SCREENS.remove(display);
    }

    static synchronized List<Panel> snapshot(Level level, boolean render) {
        List<Panel> panels = new ArrayList<>();
        for (DisplayBlock.Entity display : SCREENS.keySet()) {
            if (display.isRemoved() || display.getLevel() != level
                    || display.isImageViewer() && !display.getBlockState().getValue(ImageViewerBlock.FRAMED)) {
                continue;
            }
            var sub = SableCompanion.INSTANCE.getContaining(level, display.getBlockPos());
            Pose3dc pose = sub == null ? null : render && sub instanceof ClientSubLevelAccess client
                    ? client.renderPose() : sub.logicalPose();
            panels.add(new Panel(display.getBlockPos(), display.facing(), display.width(), display.height(), pose));
        }
        return List.copyOf(panels);
    }

    static final class Panel {
        private final BlockPos anchor;
        private final VoxelShape shape;
        private final Pose3dc pose;
        private final AABB bounds;

        Panel(BlockPos anchor, Direction facing, double width, double height, Pose3dc pose) {
            this.anchor = anchor.immutable();
            this.pose = pose == null ? null : new Pose3d(pose);
            DisplayBlock.Screen screen = new DisplayBlock.Screen(facing);
            Vec3 offset = Vec3.atLowerCornerOf(anchor);
            shape = Shapes.create(new AABB(offset.add(screen.at(0, 0, -(float) DisplayBlock.DEPTH)),
                    offset.add(screen.at((float) width, (float) height, 0.004f))));
            AABB local = shape.bounds();
            AABB world = local;
            if (this.pose != null) {
                world = null;
                for (int i = 0; i < 8; i++) {
                    Vec3 corner = this.pose.transformPosition(new Vec3((i & 1) == 0 ? local.minX : local.maxX,
                            (i & 2) == 0 ? local.minY : local.maxY, (i & 4) == 0 ? local.minZ : local.maxZ));
                    world = world == null ? new AABB(corner, corner) : world.minmax(new AABB(corner, corner));
                }
            }
            bounds = world.inflate(1e-6);
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
