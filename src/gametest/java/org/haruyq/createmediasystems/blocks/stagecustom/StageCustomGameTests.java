package org.haruyq.createmediasystems.blocks.stagecustom;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Cymbal;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Pedal;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;
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
        var heard = new ArrayList<CustomPacketPayload>();
        var player = VideoPlayerGameTests.listener(level, root.south(), heard);
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
            var velocities = heard.stream().filter(StageCustomMidi.Played.class::isInstance)
                    .map(StageCustomMidi.Played.class::cast).map(event -> event.hit().velocity()).toList();
            helper.assertTrue(velocities.equals(List.of(1, 127, 1, 127, 1, 127, 1, 127, 1, 127)),
                    "The sound packets lost MIDI velocities or played an invalid hit");
            player.setPos(Vec3.atCenterOf(root.east(10)));
            helper.assertTrue(!StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, 38, 100)),
                    "An out-of-range player played the drums");
        } finally {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void completeKitSavesItsHiHatAndUsesTheUpperCymbalCells(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(2, 2, 2));
        var facing = Direction.NORTH;
        var state = StageCustomFeature.COMPLETE_BLOCK.get().defaultBlockState().setValue(StageCustomBlock.FACING, facing)
                .setValue(StageCustomBlock.LARGE, true);
        prepare(level, root, facing, state);
        var upper = Part.TOP_LEFT.at(root, facing);
        level.setBlockAndUpdate(upper, Blocks.COBBLESTONE.defaultBlockState());
        helper.assertTrue(!StageCustomBlock.install(level, root, state, null) && level.isEmptyBlock(root),
                "The complete kit ignored a ceiling obstruction");
        level.removeBlock(upper, false);
        helper.assertTrue(StageCustomBlock.install(level, root, state, null), "The complete kit did not fit");
        for (var part : StageCustomBlock.parts(state)) {
            var pos = part.at(root, facing);
            var installed = level.getBlockState(pos);
            helper.assertTrue(installed.is(StageCustomFeature.COMPLETE_BLOCK.get())
                            && StageCustomBlock.root(pos, installed).equals(root) && !installed.getShape(level, pos).isEmpty(),
                    "An enlarged part was missing or untargetable " + part);
        }

        var kit = (StageCustomBlock.Entity) level.getBlockEntity(root);
        var mock = helper.makeMockPlayer(GameType.SURVIVAL);
        var hat = StageCustomCymbals.plate(Cymbal.HI_HAT);
        Vec3 hatAt = StageCustomLayout.toWorld(root, facing, hat.point(0, 0, hat.profile(0)).scale(StageCustomBlock.scale(state)));
        mock.setPos(hatAt.add(0, .38 - mock.getEyeHeight(), 0));
        mock.setShiftKeyDown(true);
        var at = BlockPos.containing(hatAt);
        var click = new BlockHitResult(hatAt, Direction.UP, at, false);
        helper.assertTrue(level.getBlockState(at).useWithoutItem(level, mock, click).consumesAction() && !kit.hatOpen(),
                "Sneak-using the kit worked the hi-hat instead of opening its screen");
        mock.setShiftKeyDown(false);
        Vec3 pedalAt = StageCustomLayout.toWorld(root, facing, StageCustomCymbals.hinge(Pedal.HI_HAT).scale(StageCustomBlock.scale(state)));
        mock.setPos(pedalAt.add(0, .3 - mock.getEyeHeight(), 0));
        var pedal = BlockPos.containing(pedalAt);
        helper.assertTrue(level.getBlockState(pedal).useWithoutItem(level, mock, new BlockHitResult(pedalAt, Direction.UP, pedal, false))
                .consumesAction() && kit.hatOpen(), "The hi-hat pedal did not open the hi-hat");
        var reloaded = new StageCustomBlock.Entity(root, state);
        reloaded.loadWithComponents(kit.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        helper.assertTrue(reloaded.hatOpen(), "The open hi-hat was lost on save and load");

        var packets = new ArrayList<CustomPacketPayload>();
        var player = VideoPlayerGameTests.listener(level, root.north(2), packets);
        try {
            player.setShiftKeyDown(true);
            helper.assertTrue(level.getBlockState(at).useWithoutItem(level, player, click).consumesAction()
                    && packets.stream().anyMatch(StageCustomMidi.Open.class::isInstance), "Sneak-using the kit did not open its screen");
            player.setShiftKeyDown(false);
            for (var voice : Voice.values()) {
                int note = StageCustomMidi.note(voice);
                helper.assertTrue(StageCustomMidi.strike(player, new StageCustomMidi.Hit(root, note, 93)),
                        "MIDI could not reach a cymbal: " + voice);
            }
            helper.assertTrue(!kit.hatOpen() && !kit.getUpdateTag(level.registryAccess()).getBoolean("HatOpen"),
                    "The MIDI foot pedal did not close and sync the hi-hat");
            var notes = packets.stream().filter(StageCustomMidi.Played.class::isInstance)
                    .map(StageCustomMidi.Played.class::cast).map(event -> event.hit().note()).toList();
            helper.assertTrue(notes.equals(List.of(42, 46, 44, 49, 51, 53)), "Wrong samples for the cymbal pads");
            packets.clear();
            player.setPos(Vec3.atCenterOf(root.east(10)));
            StageCustomMidi.open(player, root);
            helper.assertTrue(packets.stream().noneMatch(StageCustomMidi.Open.class::isInstance), "The kit's screen opened out of reach");
        } finally {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }

        level.destroyBlock(upper, true);
        for (var part : StageCustomBlock.parts(state)) {
            helper.assertTrue(level.isEmptyBlock(part.at(root, facing)), "Breaking an upper tile detached an orphan part");
        }
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(3),
                item -> item.getItem().is(StageCustomFeature.COMPLETE_ITEM.get()));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "The wrong kit was dropped");
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
