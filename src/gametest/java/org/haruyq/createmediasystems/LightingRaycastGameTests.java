package org.haruyq.createmediasystems;

import org.haruyq.createmediasystems.blocks.display.DisplayFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.haruyq.createmediasystems.blocks.display.DisplayBlock;
import org.haruyq.createmediasystems.lighting.LightingRaycast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightingRaycastGameTests {
    private LightingRaycastGameTests() {
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 100)
    public static void emptySectionsKeepVanillaHitsAtBoundariesAndInsideThinShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = new BlockPos(-128, 240, -128);
        Random random = new Random(1708);
        for (int x = -12; x <= -5; x++) {
            for (int z = -12; z <= -5; z++) {
                level.getChunk(x, z);
            }
        }
        BlockState[] states = {Blocks.STONE.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState()};
        List<BlockPos> targets = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            BlockPos target = center.offset(random.nextInt(-48, 49), random.nextInt(-32, 33), random.nextInt(-48, 49));
            level.setBlockAndUpdate(target, states[i % states.length]);
            targets.add(target);
        }
        LightingRaycast rays = LightingRaycast.rendered(level, center.above(70));
        helper.assertTrue(rays.clip(Vec3.ZERO, Vec3.ZERO).block().getType() == HitResult.Type.MISS
                        && rays.clip(Vec3.ZERO, new Vec3(-Double.MIN_VALUE, 0, 0)).block().getType() == HitResult.Type.MISS,
                "zero and subnormal rays must finish without a hit");
        for (int i = 0; i < 2048; i++) {
            Vec3 to = targets.get(i % targets.size()).getCenter();
            Vec3 from = Vec3.atLowerCornerOf(center).add(random.nextDouble(-48, 48), random.nextDouble(-32, 32),
                    random.nextDouble(-48, 48));
            if (i % 4 == 0) {
                from = to;
                to = from.add(random.nextDouble(-20, 20), random.nextDouble(-20, 20), random.nextDouble(-20, 20));
            } else if (i % 4 == 1) {
                from = Vec3.atLowerCornerOf(targets.get(i % targets.size())).add(-32, 0, -32);
                to = from.add(64, 0, 64);
            } else if (i % 4 == 2) {
                from = to.add(i % 3 == 0 ? -48 : 0, i % 3 == 1 ? -32 : 0, i % 3 == 2 ? -48 : 0);
            }
            BlockHitResult expected = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            BlockHitResult actual = rays.clip(from, to).block();
            helper.assertTrue(actual.getType() == expected.getType(), "hit type differs on ray " + i);
            if (expected.getType() == HitResult.Type.BLOCK) {
                helper.assertTrue(actual.getBlockPos().equals(expected.getBlockPos())
                                && actual.getDirection() == expected.getDirection() && actual.isInside() == expected.isInside()
                                && actual.getLocation().distanceToSqr(expected.getLocation()) < 1e-18,
                        "hit differs on ray " + i + ": " + from + " to " + to);
            }
        }
        targets.forEach(target -> level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()));
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void visibilityReusesAnOriginAndRefreshesOnTheNextSnapshot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos wall = source.offset(2, 0, 0);
        Vec3 from = source.getCenter();
        Vec3 to = from.add(4, 0, 0);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        LightingRaycast rays = LightingRaycast.rendered(level, source);
        helper.assertTrue(!rays.visible(from, to) && !rays.visible(from, to), "the cached corner stays occluded");
        helper.assertTrue(rays.visible(to.add(1, 0, 0), to), "another origin must not reuse the occlusion");
        helper.assertTrue(!rays.visible(from, to), "returning to the first origin must still hit the wall");
        level.removeBlock(wall, false);
        helper.assertTrue(LightingRaycast.rendered(level, source).visible(from, to), "the next snapshot must see the removed wall");
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void worldWallStopsBeam(GameTestHelper helper) {
        BlockPos source = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wall = helper.absolutePos(new BlockPos(4, 2, 2));
        helper.getLevel().setBlockAndUpdate(source, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        LightingRaycast.Hit hit = LightingRaycast.logical(helper.getLevel(), source)
                .clip(source.getCenter(), wall.getCenter().add(1, 0, 0));
        helper.assertTrue(hit.block().getBlockPos().equals(wall), "beam did not stop at wall: " + hit.block().getBlockPos());
        helper.succeed();
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void displayPanelOccludesItsWholeRectangleAndClearsOnRemoval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos display = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(display, DisplayFeature.DISPLAY.get().defaultBlockState()
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
            LightingRaycast.Hit nearHit = LightingRaycast.logical(level, BlockPos.containing(from)).clip(from, to);
            helper.assertTrue(nearHit.block().getType() == HitResult.Type.BLOCK
                            && nearHit.block().getBlockPos().equals(nearWall),
                    "a closer wall must win before the screen: " + nearHit.block().getBlockPos());
            level.setBlockAndUpdate(nearWall, Blocks.AIR.defaultBlockState());
            LightingRaycast.Hit screenHit = LightingRaycast.logical(level, BlockPos.containing(from)).clip(from, to);
            helper.assertTrue(screenHit.block().getType() == HitResult.Type.BLOCK
                            && screenHit.block().getBlockPos().equals(display),
                    "screen must occlude the full framed rectangle before the wall: " + screenHit.block().getBlockPos());
            level.setBlockAndUpdate(display, Blocks.AIR.defaultBlockState());
            LightingRaycast.Hit wallHit = LightingRaycast.logical(level, BlockPos.containing(from)).clip(from, to);
            helper.assertTrue(wallHit.block().getType() == HitResult.Type.BLOCK
                            && wallHit.block().getBlockPos().equals(farWall),
                    "removed screen must no longer occlude the wall: " + wallHit.block().getBlockPos());
            helper.succeed();
        });
    }

    @GameTest(batch = "lighting_raycast", template = "empty", timeoutTicks = 20)
    public static void unloadedChunksStopBeamsWithoutBeingLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 from = new Vec3(1_000_000.5, 100.5, 1_000_000.5);
        BlockPos pos = BlockPos.containing(from);
        helper.assertTrue(level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null,
                "test location starts unloaded");
        LightingRaycast.Hit hit = LightingRaycast.logical(level, helper.absolutePos(BlockPos.ZERO)).clip(from, from.add(4, 0, 0));
        helper.assertTrue(hit.block().getType() == HitResult.Type.BLOCK && hit.point().distanceTo(from) < 0.01,
                "unknown terrain must stop the beam at its boundary");
        helper.assertTrue(level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null,
                "a lighting ray must not load a chunk");
        helper.succeed();
    }
}
