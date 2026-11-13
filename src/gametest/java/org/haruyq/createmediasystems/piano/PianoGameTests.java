package org.haruyq.createmediasystems.piano;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.haruyq.createmediasystems.CreateMediaSystems;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class PianoGameTests {
    @GameTest(template = "empty")
    public static void bearingCarriesTheEntirePianoWithOneGluedPart(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos root = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos deck = root.below(), bearingPos = deck.below();
        var state = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.OPEN, true);
        level.setBlockAndUpdate(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState()
                .setValue(DirectionalBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(deck, Blocks.IRON_BLOCK.defaultBlockState());
        helper.assertTrue(GrandPianoBlock.placeParts(level, root, state), "Piano placement failed");
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(deck).deflate(.2).minmax(new AABB(root).deflate(.2))));

        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        helper.assertTrue(carrier != null && carrier.getContraption().getBlocks().size() == 7,
                "One glued piano part must bring the whole instrument onto the bearing");
        var local = root.subtract(deck);
        var before = MountedPiano.frame(carrier, local, state);
        for (var part : GrandPianoBlock.Part.values()) {
            var pos = part.at(root, state);
            helper.assertTrue(level.getBlockState(pos).isAir(), "A piano part was left behind: " + part);
            var captured = carrier.getContraption().getBlocks().get(pos.subtract(deck));
            helper.assertTrue(captured != null && captured.state().getValue(GrandPianoBlock.PART) == part,
                    "Missing carried part " + part);
        }
        carrier.setAngle(90);
        var after = MountedPiano.frame(carrier, local, state);
        var landedRoot = BlockPos.containing(carrier.toGlobalVector(local.getCenter(), 1));
        helper.assertTrue(after.lidOpen() && after.origin().distanceTo(before.origin()) > .5,
                "The open instrument's soundboard did not rotate with the bearing");
        bearing.disassemble();
        var landed = level.getBlockState(landedRoot);
        helper.assertTrue(landed.is(PianoFeature.PIANO.get()) && landed.getValue(GrandPianoBlock.OPEN),
                "The disassembled piano did not retain its open lid");
        for (var part : GrandPianoBlock.Part.values()) {
            var pos = part.at(landedRoot, landed);
            var restored = level.getBlockState(pos);
            helper.assertTrue(restored.is(PianoFeature.PIANO.get()) && restored.getValue(GrandPianoBlock.PART) == part
                    && GrandPianoBlock.root(pos, restored).equals(landedRoot), "Restored part has the wrong position " + part);
        }
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(5)).isEmpty(),
                "Moving the piano dropped a duplicate instrument");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sidewaysDisassemblyRotatesAllPartsAndTheSoundboard(GameTestHelper helper) {
        var upright = PianoFeature.PIANO.get().defaultBlockState();
        var offset = new BlockPos(2, 4, -3);
        for (var axis : Direction.Axis.values()) {
            var transform = new StructureTransform(offset, axis, Rotation.CLOCKWISE_90, Mirror.NONE);
            var rotated = transform.apply(upright);
            var root = transform.apply(BlockPos.ZERO);
            for (var part : GrandPianoBlock.Part.values()) {
                var original = part.at(BlockPos.ZERO, upright);
                var landed = transform.apply(original);
                var state = transform.apply(upright.setValue(GrandPianoBlock.PART, part));
                helper.assertTrue(part.at(root, rotated).equals(landed) && GrandPianoBlock.root(landed, state).equals(root),
                        "The " + axis + " transform separated " + part + " from the keyboard");
            }
            var before = GrandPianoBlock.soundboard(BlockPos.ZERO, upright);
            helper.assertTrue(GrandPianoBlock.soundboard(root, rotated).distanceTo(transform.apply(before)) < .00001,
                    "The " + axis + " transform misplaced the soundboard");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void grandPianoPlacesAndDropsAsOneInstrument(GameTestHelper helper) {
        var level = helper.getLevel();
        var root = helper.absolutePos(new BlockPos(2, 2, 2));
        var item = PianoFeature.PIANO_ITEM.get();
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(90);
        player.setPos(root.getX() + 2.5, root.getY(), root.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        Direction facing = player.getDirection().getOpposite();
        var placement = PianoFeature.PIANO.get().defaultBlockState().setValue(GrandPianoBlock.FACING, facing);
        for (var part : GrandPianoBlock.Part.values()) {
            level.setBlockAndUpdate(part.at(root, placement).below(), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        var hit = new BlockHitResult(Vec3.atCenterOf(root.below()).add(0, .5, 0), Direction.UP, root.below(), false);
        var context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        var tail = GrandPianoBlock.Part.TAIL.at(root, placement);
        level.setBlockAndUpdate(tail, Blocks.STONE.defaultBlockState());
        helper.assertFalse(item.place(context).consumesAction(), "The occupied tail should prevent placement");
        helper.assertTrue(level.getBlockState(root).isAir() && player.getMainHandItem().is(item),
                "An obstructed placement changed the root or the item");
        level.removeBlock(tail, false);
        helper.assertTrue(item.place(context).consumesAction() && player.getMainHandItem().isEmpty(),
                "The item did not place the full piano");
        for (var part : GrandPianoBlock.Part.values()) {
            var pos = part.at(root, placement);
            var state = level.getBlockState(pos);
            helper.assertTrue(state.is(PianoFeature.PIANO.get()) && GrandPianoBlock.root(pos, state).equals(root),
                    "Missing or incorrectly rotated piano part " + part);
            helper.assertTrue((level.getBlockEntity(pos) instanceof PianoBlockEntity) == (part == GrandPianoBlock.Part.KEYBOARD),
                    "Unexpected block entity on " + part);
        }
        var piano = (PianoBlockEntity) level.getBlockEntity(root);
        piano.toggleLid();
        helper.assertTrue(level.getBlockState(root).getValue(GrandPianoBlock.OPEN) && level.getBlockEntity(root) == piano,
                "Toggling the lid did not keep the piano");
        level.destroyBlock(tail, true, player);
        for (var part : GrandPianoBlock.Part.values()) {
            helper.assertTrue(level.getBlockState(part.at(root, placement)).isAir(), "Orphaned piano part " + part);
        }
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(4),
                entity -> entity.getItem().is(item));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "Expected exactly one piano drop");
        helper.assertTrue(level.getRecipeManager().byKey(CreateMediaSystems.id("grand_piano"))
                .filter(recipe -> recipe.value().getResultItem(level.registryAccess()).is(item)).isPresent(),
                "The shaped recipe is missing");
        helper.succeed();
    }
}
