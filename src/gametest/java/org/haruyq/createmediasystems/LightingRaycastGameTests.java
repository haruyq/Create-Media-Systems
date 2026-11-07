package org.haruyq.createmediasystems;

import java.util.ArrayList;
import java.util.List;

import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.lighting.LightingRaycast;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightingRaycastGameTests {
    private static final List<ServerSubLevel> RETIRED = new ArrayList<>();

    private LightingRaycastGameTests() {
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void visibilityReusesAnOriginAndRefreshesOnTheNextSnapshot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos wall = source.offset(2, 0, 0);
        Vec3 from = source.getCenter();
        Vec3 to = from.add(4, 0, 0);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        LightingRaycast rays = new LightingRaycast(level, source, true);
        helper.assertTrue(!rays.visible(from, to) && !rays.visible(from, to), "the cached corner stays occluded");
        helper.assertTrue(rays.visible(to.add(1, 0, 0), to), "another origin must not reuse the occlusion");
        helper.assertTrue(!rays.visible(from, to), "returning to the first origin must still hit the wall");
        level.removeBlock(wall, false);
        helper.assertTrue(new LightingRaycast(level, source, true).visible(from, to), "the next snapshot must see the removed wall");
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void worldWallStopsBeam(GameTestHelper helper) {
        BlockPos source = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wall = helper.absolutePos(new BlockPos(4, 2, 2));
        helper.getLevel().setBlockAndUpdate(source, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        LightingRaycast.Hit hit = new LightingRaycast(helper.getLevel(), source, false)
                .clip(source.getCenter(), wall.getCenter().add(1, 0, 0));
        helper.assertTrue(hit.block().getBlockPos().equals(wall), "beam did not stop at wall: " + hit.block().getBlockPos());
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void displayPanelOccludesItsWholeRectangleAndClearsOnRemoval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos display = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(display, CreateMediaSystems.DISPLAY.get().defaultBlockState()
                .setValue(DisplayBlock.FACING, Direction.NORTH));
        if (level.getBlockEntity(display) instanceof DisplayBlock.Entity entity) {
            entity.resize(32, 16, DisplayBlock.Ratio.WIDE);
        }
        helper.runAfterDelay(1, () -> {
            Vec3 target = Vec3.atLowerCornerOf(display).add(new DisplayBlock.Screen(Direction.NORTH)
                    .at(1.5f, 0.5f, 0));
            Vec3 from = target.add(new DisplayBlock.Screen(Direction.NORTH).out().scale(3));
            Vec3 to = target.subtract(new DisplayBlock.Screen(Direction.NORTH).out().scale(5));
            BlockPos nearWall = BlockPos.containing(target.add(new DisplayBlock.Screen(Direction.NORTH).out().scale(1.5)));
            BlockPos farWall = BlockPos.containing(target.subtract(new DisplayBlock.Screen(Direction.NORTH).out().scale(4)));
            level.setBlockAndUpdate(nearWall, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(farWall, Blocks.STONE.defaultBlockState());
            LightingRaycast.Hit nearHit = new LightingRaycast(level, BlockPos.containing(from), false).clip(from, to);
            helper.assertTrue(nearHit.block().getType() == HitResult.Type.BLOCK
                            && nearHit.block().getBlockPos().equals(nearWall),
                    "a closer wall must win before the screen: " + nearHit.block().getBlockPos());
            level.setBlockAndUpdate(nearWall, Blocks.AIR.defaultBlockState());
            LightingRaycast.Hit screenHit = new LightingRaycast(level, BlockPos.containing(from), false).clip(from, to);
            helper.assertTrue(screenHit.block().getType() == HitResult.Type.BLOCK
                            && screenHit.block().getBlockPos().equals(display),
                    "screen must occlude the full framed rectangle before the wall: " + screenHit.block().getBlockPos());
            level.setBlockAndUpdate(display, Blocks.AIR.defaultBlockState());
            LightingRaycast.Hit wallHit = new LightingRaycast(level, BlockPos.containing(from), false).clip(from, to);
            helper.assertTrue(wallHit.block().getType() == HitResult.Type.BLOCK
                            && wallHit.block().getBlockPos().equals(farWall),
                    "removed screen must no longer occlude the wall: " + wallHit.block().getBlockPos());
            helper.succeed();
        });
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void enlargedDisplayOnRotatedScaledShipUsesMovedBlockEntityRegistration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockState state = CreateMediaSystems.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING,
                Direction.NORTH);
        level.setBlockAndUpdate(origin, state);
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(level, origin, List.of(origin),
                new BoundingBox3i(origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getY(), origin.getZ()));
        BlockPos plot = ship.getPlot().getCenterBlock();
        helper.assertTrue(level.getBlockState(plot).is(state.getBlock()), "display was not assembled onto the ship");
        if (level.getBlockEntity(plot) instanceof DisplayBlock.Entity entity) {
            entity.resize(64, 16, DisplayBlock.Ratio.WIDE);
        } else {
            helper.assertTrue(false, "assembled display block entity was not loaded");
            remove(ship);
            return;
        }
        ship.logicalPose().scale().set(1.8, 0.7, 1.4);
        ship.logicalPose().orientation().rotationY(0.55).rotateX(0.18);
        update(ship);
        helper.runAfterDelay(1, () -> {
            DisplayBlock.Screen screen = new DisplayBlock.Screen(Direction.NORTH);
            Vec3 local = Vec3.atLowerCornerOf(plot).add(screen.at(3.5f, 0.5f, 0));
            Vec3 from = world(ship, local.add(screen.out().scale(3)));
            Vec3 to = world(ship, local.subtract(screen.out().scale(5)));
            LightingRaycast.Hit hit = new LightingRaycast(level, BlockPos.containing(from), false).clip(from, to);
            helper.assertTrue(hit.block().getType() == HitResult.Type.BLOCK
                            && hit.block().getBlockPos().equals(plot),
                    "moved enlarged display must occlude outside its anchor: " + hit.block().getBlockPos());
            Vec3 tangentRight = world(ship, local.add(screen.right())).subtract(world(ship, local));
            Vec3 tangentUp = world(ship, local.add(0, 1, 0)).subtract(world(ship, local));
            helper.assertTrue(Math.abs(hit.normal().dot(tangentRight)) < 1e-5
                            && Math.abs(hit.normal().dot(tangentUp)) < 1e-5,
                    "display normal must be perpendicular to the transformed panel");
            remove(ship);
            helper.succeed();
        });
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void groundBeamHitsARotatedShipThinShapeBeforeWorldWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel ship = ship(helper, new BlockPos(2, 3, 2), Blocks.GLASS_PANE.defaultBlockState());
        try {
            BlockPos plot = ship.getPlot().getCenterBlock();
            ship.logicalPose().orientation().rotationY(0.7).rotateX(0.25);
            update(ship);
            Vec3 from = world(ship, plot.getCenter().add(0, 0, -3));
            Vec3 to = world(ship, plot.getCenter().add(0, 0, 3));
            loadPath(level, from, to);
            level.setBlockAndUpdate(BlockPos.containing(to), Blocks.STONE.defaultBlockState());
            LightingRaycast.Hit hit = new LightingRaycast(level, BlockPos.containing(from), false).clip(from, to);
            assertHit(helper, hit, plot, world(ship, new Vec3(plot.getX() + 0.5, plot.getY() + 0.5, plot.getZ() + 7 / 16.0)));
        } finally {
            remove(ship);
        }
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void shipBeamIgnoresItsFixtureAndStopsAtWorldWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel ship = ship(helper, new BlockPos(2, 3, 2), Blocks.STONE.defaultBlockState());
        try {
            BlockPos plot = ship.getPlot().getCenterBlock();
            ship.logicalPose().orientation().rotationY(0.9).rotateX(0.2);
            update(ship);
            Vec3 from = world(ship, plot.getCenter());
            BlockPos wall = BlockPos.containing(from.add(4, 0, 0));
            Vec3 to = new Vec3(wall.getX() + 2, from.y, from.z);
            loadPath(level, from, to);
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            LightingRaycast.Hit hit = new LightingRaycast(level, plot, false).clip(from, to);
            assertHit(helper, hit, wall, new Vec3(wall.getX(), from.y, from.z));
        } finally {
            remove(ship);
        }
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void shipBeamChoosesTheNearestShipBeforeGroundWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel source = ship(helper, new BlockPos(2, 3, 2), Blocks.STONE.defaultBlockState());
        ServerSubLevel target = ship(helper, new BlockPos(4, 3, 2), Blocks.STONE.defaultBlockState());
        try {
            BlockPos sourcePlot = source.getPlot().getCenterBlock();
            BlockPos targetPlot = target.getPlot().getCenterBlock();
            source.logicalPose().orientation().rotationY(-0.4);
            update(source);
            Vec3 from = world(source, sourcePlot.getCenter());
            target.logicalPose().orientation().rotationY(0.6).rotateZ(0.2);
            Vec3 delta = from.add(4, 0, 0).subtract(world(target, targetPlot.getCenter()));
            target.logicalPose().position().add(delta.x, delta.y, delta.z);
            update(target);
            Vec3 to = from.add(8, 0, 0);
            loadPath(level, from, to);
            level.setBlockAndUpdate(BlockPos.containing(from.add(6, 0, 0)), Blocks.STONE.defaultBlockState());
            LightingRaycast.Hit hit = new LightingRaycast(level, sourcePlot, false).clip(from, to);
            helper.assertTrue(hit.block().getType() == HitResult.Type.BLOCK && hit.block().getBlockPos().equals(targetPlot),
                    "ship-to-ship beam must choose the nearer ship, got " + hit.block().getBlockPos());
            helper.assertTrue(hit.point().distanceTo(from) < 5, "hit distance must be measured in world space");
        } finally {
            remove(target);
            remove(source);
        }
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void movingShipUpdatesNextFrameWithoutMutatingTheCurrentSnapshot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel ship = ship(helper, new BlockPos(2, 3, 2), Blocks.STONE.defaultBlockState());
        try {
            BlockPos plot = ship.getPlot().getCenterBlock();
            Vec3 from = world(ship, plot.getCenter().add(0, 0, -3));
            Vec3 to = world(ship, plot.getCenter().add(0, 0, 3));
            loadPath(level, from, to);
            BlockPos ignored = BlockPos.containing(from).below(10);
            LightingRaycast frame = new LightingRaycast(level, ignored, false);
            AABB nearby = new AABB(from, to).inflate(1);
            helper.assertTrue(frame.hasSubLevels(nearby), "the ship intersects the beam's region");
            helper.assertTrue(!frame.hasSubLevels(nearby.move(0, 1000, 0)),
                    "a distant ship must not force an unrelated light to rescan");
            LightingRaycast.Hit before = frame.clip(from, to);
            helper.assertTrue(before.block().getBlockPos().equals(plot), "initial ship is visible to the beam");
            ship.logicalPose().position().add(8, 0, 0);
            ship.logicalPose().orientation().rotationY(1.1).rotateX(0.35);
            update(ship);
            helper.assertTrue(frame.hasSubLevels(nearby), "the current frame keeps its captured bounds");
            helper.assertTrue(!new LightingRaycast(level, ignored, false).hasSubLevels(nearby),
                    "the next frame must release the region after the ship leaves");
            LightingRaycast.Hit sameFrame = frame.clip(from, to);
            helper.assertTrue(sameFrame.point().distanceTo(before.point()) < 1e-6,
                    "all rays in one frame use the same pose snapshot");
            helper.assertTrue(new LightingRaycast(level, ignored, false).clip(from, to).block().getType() == HitResult.Type.MISS,
                    "next frame must not hit the ship at its old position");
            Vec3 movedFrom = world(ship, plot.getCenter().add(0, 0, -3));
            Vec3 movedTo = world(ship, plot.getCenter().add(0, 0, 3));
            loadPath(level, movedFrom, movedTo);
            LightingRaycast.Hit moved = new LightingRaycast(level, ignored, false).clip(movedFrom, movedTo);
            helper.assertTrue(moved.point().distanceTo(world(ship, plot.getCenter().add(0, 0, -0.5))) < 1e-5,
                    "moved native hit=" + moved.block().getLocation() + ", plot=" + plot
                            + ", state=" + level.getBlockState(plot) + ", inside=" + moved.block().isInside());
            assertHit(helper, moved, plot, world(ship, plot.getCenter().add(0, 0, -0.5)));
        } finally {
            remove(ship);
        }
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void scaledRotatedShipReturnsWorldSurfacePointAndNormal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel ship = ship(helper, new BlockPos(2, 3, 2), Blocks.STONE.defaultBlockState());
        try {
            BlockPos plot = ship.getPlot().getCenterBlock();
            ship.logicalPose().scale().set(2.5, 0.6, 1.3);
            ship.logicalPose().orientation().rotationY(0.65).rotateX(0.4).rotateZ(0.2);
            update(ship);
            Vec3 from = world(ship, plot.getCenter().add(-3, 0, 0));
            Vec3 to = world(ship, plot.getCenter().add(3, 0, 0));
            loadPath(level, from, to);
            LightingRaycast.Hit hit = new LightingRaycast(level, BlockPos.containing(from), false).clip(from, to);
            Vec3 localImpact = plot.getCenter().add(-0.5, 0, 0);
            assertHit(helper, hit, plot, world(ship, localImpact));
            Vec3 tangentY = world(ship, localImpact.add(0, 1, 0)).subtract(hit.point());
            Vec3 tangentZ = world(ship, localImpact.add(0, 0, 1)).subtract(hit.point());
            helper.assertTrue(Math.abs(hit.normal().dot(tangentY)) < 1e-6
                    && Math.abs(hit.normal().dot(tangentZ)) < 1e-6, "normal must be perpendicular to the scaled surface");
            helper.assertTrue(Math.abs(hit.normal().length() - 1) < 1e-6 && hit.normal().dot(to.subtract(from)) < 0,
                    "normal is a unit world-space vector facing the source");
        } finally {
            remove(ship);
        }
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void unloadedChunksStopBeamsWithoutBeingLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 from = new Vec3(1_000_000.5, 100.5, 1_000_000.5);
        BlockPos pos = BlockPos.containing(from);
        helper.assertTrue(level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null,
                "test location starts unloaded");
        LightingRaycast.Hit hit = new LightingRaycast(level, helper.absolutePos(BlockPos.ZERO), false).clip(from, from.add(4, 0, 0));
        helper.assertTrue(hit.block().getType() == HitResult.Type.BLOCK && hit.point().distanceTo(from) < 0.01,
                "unknown terrain must stop the beam at its boundary");
        helper.assertTrue(level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null,
                "a lighting ray must not load a chunk");
        helper.succeed();
    }

    private static ServerSubLevel ship(GameTestHelper helper, BlockPos relative, BlockState state) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(relative);
        level.setBlockAndUpdate(origin, state);
        ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(level, origin, List.of(origin),
                new BoundingBox3i(origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getY(), origin.getZ()));
        helper.assertTrue(level.getBlockState(ship.getPlot().getCenterBlock()).equals(state), "assembly did not copy the test block");
        ship.logicalPose().position().add(0, 12, 0);
        update(ship);
        return ship;
    }

    private static void update(ServerSubLevel ship) {
        ship.updateBoundingBox();
        ship.forceUpdateGlobalBounds();
    }

    private static Vec3 world(ServerSubLevel ship, Vec3 local) {
        return ship.logicalPose().transformPosition(local);
    }

    private static void loadPath(ServerLevel level, Vec3 from, Vec3 to) {
        level.getChunk(BlockPos.containing(from));
        level.getChunk(BlockPos.containing(to));
    }

    private static void assertHit(GameTestHelper helper, LightingRaycast.Hit hit, BlockPos expected, Vec3 point) {
        helper.assertTrue(hit.block().getType() == HitResult.Type.BLOCK && hit.block().getBlockPos().equals(expected),
                "expected native block " + expected + ", got " + hit.block().getBlockPos());
        helper.assertTrue(hit.point().distanceTo(point) < 1e-5,
                "expected world impact " + point + ", got " + hit.point() + ", native=" + hit.block().getLocation()
                        + ", plot=" + expected + ", state=" + helper.getLevel().getBlockState(expected)
                        + ", inside=" + hit.block().isInside());
    }

    private static void remove(ServerSubLevel ship) {
        RETIRED.add(ship);
    }

    @AfterBatch(batch = "lighting_raycast")
    public static void cleanup(ServerLevel level) {
        // Plot deletion is queued; keep plots reserved until all traces in this batch finish.
        for (ServerSubLevel ship : RETIRED) {
            delete(ship);
        }
        RETIRED.clear();
    }

    private static void delete(ServerSubLevel ship) {
        var container = SubLevelContainer.getContainer(ship.getLevel());
        var plot = ship.getPlot().plotPos;
        var origin = container.getOrigin();
        int x = plot.x - origin.x;
        int z = plot.z - origin.y;
        if (container.getSubLevel(x, z) == ship) {
            container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
        }
    }
}
