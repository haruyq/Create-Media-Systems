package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.haruyq.createmediasystems.blocks.display.DisplayBlock;

import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class LightingScreensTest {
    private static final BlockPos ANCHOR = new BlockPos(4, 7, -2);

    /** A 2 by 2 display's screen at {@code anchor}, as it stops light. */
    private static LightingScreens.Panel panel(BlockPos anchor, Direction facing, Pose3d pose) {
        return new LightingScreens.Panel(anchor, DisplayBlock.box(anchor, facing, 2, 2, 0, 0.004f), pose);
    }

    @Test
    void clipsTheWholeRectangleForEveryHorizontalFacing() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            LightingScreens.Panel panel = panel(ANCHOR, facing, null);
            DisplayPoint center = displayPoint(ANCHOR, facing, 1, 1, 0);
            LightingRaycast.Hit front = panel.clip(center.point.add(center.out.scale(2)),
                    center.point.subtract(center.out.scale(2)));
            LightingRaycast.Hit back = panel.clip(center.point.subtract(center.out.scale(2)),
                    center.point.add(center.out.scale(2)));

            assertNotNull(front, facing + " front side should be opaque");
            assertNotNull(back, facing + " back side should be opaque");
            assertEquals(ANCHOR, front.block().getBlockPos());
            assertEquals(ANCHOR, back.block().getBlockPos());
            assertTrue(front.normal().length() > 0.99 && front.normal().length() < 1.01);
        }
    }

    @Test
    void missesOutsideTheRectangleRatherThanTreatingTheAnchorAsThePanel() {
        Direction facing = Direction.NORTH;
        LightingScreens.Panel panel = panel(ANCHOR, facing, null);
        DisplayPoint outside = displayPoint(ANCHOR, facing, 2.2, 1, 0);

        assertNull(panel.clip(outside.point.add(outside.out.scale(2)), outside.point.subtract(outside.out.scale(2))));
    }

    @Test
    void copiesPoseAndTransformsHitPointAndNormal() {
        Pose3d pose = new Pose3d();
        pose.position().set(13, 3, -9);
        pose.scale().set(2.0, 0.75, 1.5);
        pose.orientation().rotateY(0.55).rotateX(0.2);
        LightingScreens.Panel panel = panel(BlockPos.ZERO, Direction.NORTH, pose);
        DisplayPoint local = displayPoint(BlockPos.ZERO, Direction.NORTH, 1, 1, 0);
        LightingScreens.Panel identity = panel(BlockPos.ZERO, Direction.NORTH, null);
        LightingRaycast.Hit localHit = identity.clip(local.point.add(local.out.scale(2)),
                local.point.subtract(local.out.scale(2)));
        Vec3 from = pose.transformPosition(local.point.add(local.out.scale(2)));
        Vec3 to = pose.transformPosition(local.point.subtract(local.out.scale(2)));
        LightingRaycast.Hit hit = panel.clip(from, to);

        assertNotNull(hit);
        assertNotNull(localHit);
        Vec3 expected = pose.transformPosition(localHit.point());
        assertEquals(expected.x, hit.point().x, 1e-6);
        assertEquals(expected.y, hit.point().y, 1e-6);
        assertEquals(expected.z, hit.point().z, 1e-6);
        DisplayBlock.Screen screen = new DisplayBlock.Screen(Direction.NORTH);
        Vec3 tangentRight = pose.transformPosition(localHit.point().add(screen.right())).subtract(hit.point());
        Vec3 tangentUp = pose.transformPosition(localHit.point().add(0, 1, 0)).subtract(hit.point());
        assertEquals(0, hit.normal().dot(tangentRight), 1e-6);
        assertEquals(0, hit.normal().dot(tangentUp), 1e-6);
        assertEquals(1, hit.normal().length(), 1e-6);

        pose.position().add(100, 100, 100);
        LightingRaycast.Hit copied = panel.clip(from, to);
        assertNotNull(copied);
        assertEquals(expected.x, copied.point().x, 1e-6);
        assertEquals(expected.y, copied.point().y, 1e-6);
        assertEquals(expected.z, copied.point().z, 1e-6);
    }

    private static DisplayPoint displayPoint(BlockPos anchorPos, Direction facing, double x, double y, double lift) {
        DisplayBlock.Screen screen = new DisplayBlock.Screen(facing);
        Vec3 anchor = Vec3.atLowerCornerOf(anchorPos);
        return new DisplayPoint(anchor.add(screen.at((float) x, (float) y, (float) lift)),
                screen.out());
    }

    private record DisplayPoint(Vec3 point, Vec3 out) {
    }
}
