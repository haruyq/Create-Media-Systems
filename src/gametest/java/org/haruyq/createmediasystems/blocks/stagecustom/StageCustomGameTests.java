package org.haruyq.createmediasystems.blocks.stagecustom;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.VideoPlayerGameTests;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomBlock.Part;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StageCustomGameTests {
    @GameTest(template = "empty")
    public static void kitPlacementChecksSupportsAndInstallsARealFootprint(GameTestHelper helper) {
        Level world = helper.getLevel();
        BlockPos root = helper.absolutePos(new BlockPos(2, 2, 2));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var state = StageCustomFeature.BLOCK.get().defaultBlockState().setValue(StageCustomBlock.FACING, facing)
                    .setValue(StageCustomBlock.LARGE, true);
            prepare(world, root, facing, state);
            helper.assertTrue(StageCustomBlock.parts(state).contains(Part.TOP) && StageCustomBlock.scale(state) == 1.5f,
                    "The enlarged heads must occupy the upper block");
            BlockPos rear = Part.LEFT_REAR.at(root, facing);
            BlockPos upper = Part.TOP.at(root, facing);
            world.setBlockAndUpdate(upper, Blocks.POLISHED_ANDESITE.defaultBlockState());
            helper.assertTrue(!StageCustomBlock.install(world, root, state, null) && world.isEmptyBlock(root),
                    "The blocked kit was partially placed");
            world.removeBlock(upper, false);
            world.removeBlock(rear.below(), false);
            helper.assertTrue(!StageCustomBlock.install(world, root, state, null) && world.isEmptyBlock(root),
                    "The kit placed without its floor support");
            world.setBlockAndUpdate(rear.below(), Blocks.STONE.defaultBlockState());
            helper.assertTrue(StageCustomBlock.install(world, root, state, null), "Unable to place " + facing);
            for (Part part : StageCustomBlock.parts(state)) {
                BlockPos installed = part.at(root, facing);
                var block = world.getBlockState(installed);
                helper.assertTrue(block.is(StageCustomFeature.BLOCK.get()) && block.getValue(StageCustomBlock.PART) == part
                        && StageCustomBlock.root(installed, block).equals(root) && !block.getShape(world, installed).isEmpty(),
                        "A part lost its anchor, orientation or shape");
            }
            helper.assertTrue(world.getBlockEntity(root) instanceof StageCustomBlock.Entity
                    && world.getBlockEntity(rear) == null, "The assembly must have only one instrument entity");
            world.removeBlock(root, false);
            helper.assertTrue(world.isEmptyBlock(rear), "A child part survived root removal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void strikingAndBreakingWorkFromChildParts(GameTestHelper helper) {
        Level world = helper.getLevel();
        BlockPos root = helper.absolutePos(new BlockPos(2, 2, 2));
        Direction facing = Direction.NORTH;
        var state = StageCustomFeature.BLOCK.get().defaultBlockState().setValue(StageCustomBlock.FACING, facing)
                .setValue(StageCustomBlock.LARGE, true);
        prepare(world, root, facing, state);
        helper.assertTrue(StageCustomBlock.install(world, root, state, null), "The kit did not fit");
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        float scale = StageCustomBlock.scale(state);
        for (Drum drum : Drum.values()) {
            var shell = StageCustomLayout.shell(drum);
            Vec3 at = StageCustomLayout.toWorld(root, facing, shell.head().scale(scale));
            Vec3 above = StageCustomLayout.toWorld(root, facing, shell.head().scale(scale).add(shell.normal().scale(.25)));
            BlockPos pos = BlockPos.containing(at);
            player.setPos(above.subtract(0, player.getEyeHeight(), 0));
            // The server can receive the click before the player's new head rotation.
            player.setXRot(-70);
            var block = world.getBlockState(pos);
            var hit = new BlockHitResult(at, Direction.UP, pos, false);
            if (drum == Drum.SNARE) {
                var stick = new ItemStack(Items.STICK);
                player.setItemInHand(InteractionHand.MAIN_HAND, stick);
                helper.assertTrue(block.useItemOn(stick, world, player, InteractionHand.MAIN_HAND, hit).consumesAction()
                        && stick.getCount() == 1, "The stick did not strike or was consumed");
            } else {
                helper.assertTrue(block.useWithoutItem(world, player, hit).consumesAction(), "The drum could not be struck " + drum);
            }
        }
        world.destroyBlock(Part.TOP.at(root, facing), true);
        for (Part part : StageCustomBlock.parts(state)) {
            helper.assertTrue(world.isEmptyBlock(part.at(root, facing)), "The broken kit left a part behind");
        }
        List<ItemEntity> drops = world.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(2),
                item -> item.getItem().is(StageCustomFeature.ITEM.get()));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "The kit did not drop as one item");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void midiPlaysTheFivePadsOnExistingKitsAndChecksItsInput(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(2, 2, 2));
        var state = StageCustomFeature.BLOCK.get().defaultBlockState();
        var facing = state.getValue(StageCustomBlock.FACING);
        prepare(level, root, facing, state);
        helper.assertTrue(StageCustomBlock.parts(state).size() == 3 && StageCustomBlock.install(level, root, state, null),
                "Previously placed three-part kits must remain usable");
        var player = VideoPlayerGameTests.listener(level, root.south(), new java.util.ArrayList<>());
        try {
            for (Drum drum : Drum.values()) {
                int note = StageCustomMidi.note(drum);
                helper.assertTrue(StageCustomMidi.drum(note) == drum && StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, note, 1)),
                        "Soft MIDI hit did not reach " + drum);
                helper.assertTrue(StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, note, 127)), "Strong MIDI hit did not reach " + drum);
            }
            helper.assertTrue(!StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, 38, 0))
                    && !StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, 38, 128))
                    && !StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, 42, 100)),
                    "The kit accepted an unmapped note or an invalid velocity");
            player.setPos(Vec3.atCenterOf(root.east(10)));
            helper.assertTrue(!StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, 38, 100)),
                    "An out-of-range player played the drums");
        } finally {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    private static void prepare(Level world, BlockPos root, Direction facing, BlockState state) {
        for (Part part : StageCustomBlock.parts(state)) {
            BlockPos at = part.at(root, facing);
            if (part.y == 0) world.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            world.removeBlock(at, false);
        }
    }
}
