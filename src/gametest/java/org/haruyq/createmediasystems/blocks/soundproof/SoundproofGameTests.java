package org.haruyq.createmediasystems.blocks.soundproof;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** What Soundproofing lets through, along a line east to west through the block at WALL. */
@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class SoundproofGameTests {
    private static final BlockPos WALL = new BlockPos(2, 2, 2);

    @GameTest(template = "empty")
    public static void aSheetSoundproofsItsBlockUntilThatGoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = helper.absolutePos(WALL);
        helper.setBlock(WALL, Blocks.STONE);
        expect(helper, 1, heard(helper, WALL.west(), WALL.east()), "a plain wall");
        Soundproofing.set(level, wall, true);
        expect(helper, Soundproofing.LAYER, heard(helper, WALL.west(), WALL.east()), "a wall with a sheet");
        // What makes the sound does not soundproof itself, whichever of its faces the sound starts on.
        expect(helper, 1, heard(helper, WALL, WALL.east()), "a sound from the sheeted block");
        Vec3 corner = Vec3.atLowerCornerOf(wall);
        for (Vec3 face : new Vec3[] {corner.add(1, .5, .5), corner.add(0, .5, .5)}) {
            for (BlockPos listener : new BlockPos[] {WALL.east(2), WALL.west(2)}) {
                boolean through = face.x > corner.x != listener.getX() > WALL.getX();
                expect(helper, through ? Soundproofing.LAYER : 1, Soundproofing.transmission(level,
                        pos -> Soundproofing.sheeted(level, pos), face, Vec3.atCenterOf(helper.absolutePos(listener))),
                        "a sound on a face of the sheeted block, heard " + (through ? "behind it" : "in front"));
            }
        }
        // A board on the face a sound starts on is in its way.
        helper.setBlock(WALL.east(), SoundproofFeature.PANEL.get().defaultBlockState().setValue(AcousticPanelBlock.FACING, Direction.EAST));
        expect(helper, Soundproofing.LAYER, Soundproofing.transmission(level, pos -> Soundproofing.sheeted(level, pos),
                corner.add(1, .5, .5), Vec3.atCenterOf(helper.absolutePos(WALL.east(2)))), "a board on the speaking face");
        helper.setBlock(WALL.east(), Blocks.AIR);
        helper.setBlock(WALL, Blocks.AIR);
        helper.assertFalse(Soundproofing.sheeted(level, wall), "The sheet stayed after its block went");
        expect(helper, 1, heard(helper, WALL.west(), WALL.east()), "where the wall was");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDoorSoundproofsOnlyClosed(GameTestHelper helper) {
        helper.setBlock(WALL.below(), Blocks.STONE);
        BlockState door = SoundproofFeature.DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        helper.setBlock(WALL, door);
        helper.setBlock(WALL.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        expect(helper, Soundproofing.LAYER, heard(helper, WALL.west(), WALL.east()), "a closed door");
        helper.setBlock(WALL, door.setValue(DoorBlock.OPEN, true));
        expect(helper, 1, heard(helper, WALL.west(), WALL.east()), "an open door");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSlidingDoorSoundproofsOnlyClosed(GameTestHelper helper) {
        helper.setBlock(WALL.below(), Blocks.STONE);
        BlockState door = SoundproofFeature.SLIDING_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        helper.setBlock(WALL, door);
        helper.setBlock(WALL.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        expect(helper, Soundproofing.LAYER, heard(helper, WALL.west(), WALL.east()), "a closed sliding door");
        helper.setBlock(WALL, door.setValue(DoorBlock.OPEN, true));
        expect(helper, 1, heard(helper, WALL.west(), WALL.east()), "an open sliding door");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theLeavesOfADoubleDoorOpenAndGoTogether(GameTestHelper helper) {
        // Facing east and hinged left, a leaf's hinge is north of it and the other leaf south.
        BlockPos other = WALL.south();
        helper.setBlock(WALL.below(), Blocks.STONE);
        helper.setBlock(other.below(), Blocks.STONE);
        BlockState left = SoundproofFeature.DOUBLE_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        BlockState right = left.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        helper.setBlock(WALL, left);
        helper.setBlock(WALL.above(), left.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(other, right);
        helper.setBlock(other.above(), right.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.assertBlockState(WALL.above(), state -> state.is(SoundproofFeature.DOUBLE_DOOR.get()), () -> "The first leaf went as the second came");
        helper.setBlock(WALL, left.setValue(DoorBlock.OPEN, true));
        for (BlockPos pos : new BlockPos[] {WALL.above(), other, other.above()}) {
            helper.assertBlockProperty(pos, DoorBlock.OPEN, true);
        }
        helper.setBlock(other.above(), right.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (BlockPos pos : new BlockPos[] {WALL, WALL.above(), other}) {
            helper.assertBlockProperty(pos, DoorBlock.OPEN, false);
        }
        helper.setBlock(other, Blocks.AIR);
        for (BlockPos pos : new BlockPos[] {WALL, WALL.above(), other.above()}) {
            helper.assertBlockNotPresent(SoundproofFeature.DOUBLE_DOOR.get(), pos);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theItemOfADoubleDoorPutsBothLeavesOrNone(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SoundproofFeature.DOUBLE_DOOR_ITEM.get()));
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
            }
        }
        BlockPos floor = WALL.below();
        UseOnContext use = new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(
                Vec3.atCenterOf(helper.absolutePos(floor)).add(0, .5, 0), Direction.UP, helper.absolutePos(floor), false));
        // No room on either side: neither leaf.
        for (Direction side : Direction.Plane.HORIZONTAL) {
            helper.setBlock(WALL.relative(side), Blocks.STONE);
        }
        player.getMainHandItem().useOn(use);
        helper.assertBlockNotPresent(SoundproofFeature.DOUBLE_DOOR.get(), WALL);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            helper.setBlock(WALL.relative(side), Blocks.AIR);
        }
        player.getMainHandItem().useOn(use);
        BlockState leaf = helper.getBlockState(WALL);
        helper.assertTrue(leaf.is(SoundproofFeature.DOUBLE_DOOR.get()), "The item put no leaf");
        BlockPos other = WALL.relative(SoundproofDoorBlock.hingeSide(leaf).getOpposite());
        for (BlockPos pos : new BlockPos[] {WALL.above(), other, other.above()}) {
            helper.assertBlockPresent(SoundproofFeature.DOUBLE_DOOR.get(), pos);
        }
        helper.assertTrue(helper.getBlockState(other).getValue(DoorBlock.HINGE) != leaf.getValue(DoorBlock.HINGE)
                && helper.getBlockState(other).getValue(DoorBlock.FACING) == leaf.getValue(DoorBlock.FACING),
                "The second leaf is not the mirror of the first");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDoorIsPointedAtByItsHandleAndStoodInOnlyByItsLeaf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(WALL);
        helper.setBlock(WALL.below(), Blocks.STONE);
        // Facing east and hinged left: the leaf on the west of its block, the hinge north.
        BlockState swing = SoundproofFeature.DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        BlockState slide = SoundproofFeature.SLIDING_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        Object[][] cases = {
                // state, where a look from the west meets it (z, sixteenths), how far before the block it does
                {swing, 12, 1}, {swing, 4, 0},
                // Open, the leaf lies along the north side.
                {swing.setValue(DoorBlock.OPEN, true), 2, 0},
                {slide, 8, 4}, {slide.setValue(DoorBlock.OPEN, true), 1, 4}};
        for (Object[] row : cases) {
            BlockState state = (BlockState) row[0];
            level.setBlock(pos, state, 3);
            Vec3 from = Vec3.atLowerCornerOf(pos).add(-2, 15 / 16.0, (int) row[1] / 16.0);
            BlockHitResult hit = level.clip(new ClipContext(from, from.add(4, 0, 0), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            helper.assertTrue(hit.getBlockPos().equals(pos) && Math.abs(hit.getLocation().x - (pos.getX() - (int) row[2] / 16.0)) < 1e-6,
                    state + " looked at through z " + row[1] + " was met at " + hit.getLocation() + " of " + hit.getBlockPos());
            helper.assertTrue(state.getCollisionShape(level, pos).bounds().minX >= 0, state + " is in the way before its block");
        }
        VoxelShape open = slide.setValue(DoorBlock.OPEN, true).getCollisionShape(level, pos);
        helper.assertTrue(open.bounds().maxZ <= 3 / 16.0, "An open sliding door is in the way of its opening");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBoardSoundproofsWhereTheLineMeetsIt(GameTestHelper helper) {
        // On the east face of the block west of it: against that block, facing east.
        helper.setBlock(WALL, SoundproofFeature.PANEL.get().defaultBlockState().setValue(AcousticPanelBlock.FACING, Direction.EAST));
        expect(helper, Soundproofing.LAYER, heard(helper, WALL.west(), WALL.east()), "through a board");
        // A listener in the board's own block, in front of it.
        expect(helper, 1, heard(helper, WALL.east(), WALL), "in front of a board");
        helper.setBlock(WALL.east(), Blocks.STONE);
        Soundproofing.set(helper.getLevel(), helper.absolutePos(WALL.east()), true);
        expect(helper, Soundproofing.LAYER * Soundproofing.LAYER, heard(helper, WALL.west(), WALL.east(2)),
                "through a board and a sheeted wall");
        Soundproofing.set(helper.getLevel(), helper.absolutePos(WALL.east()), false);
        // A wrench turns its wedges and leaves it on its wall.
        BlockState before = helper.getBlockState(WALL);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(SoundproofFeature.PANEL.get().onWrenched(before, new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(helper.absolutePos(WALL)), Direction.EAST, helper.absolutePos(WALL), false)))
                .consumesAction(), "The wrench did nothing to a panel");
        BlockState after = helper.getBlockState(WALL);
        helper.assertTrue(after.is(SoundproofFeature.PANEL.get()) && after.getValue(AcousticPanelBlock.FACING) == Direction.EAST
                && after.getValue(AcousticPanelBlock.CROSS) != before.getValue(AcousticPanelBlock.CROSS),
                "The wrench left the panel as " + after);
        helper.succeed();
    }

    private static double heard(GameTestHelper helper, BlockPos from, BlockPos to) {
        ServerLevel level = helper.getLevel();
        return Soundproofing.transmission(level, pos -> Soundproofing.sheeted(level, pos),
                Vec3.atCenterOf(helper.absolutePos(from)), Vec3.atCenterOf(helper.absolutePos(to)));
    }

    private static void expect(GameTestHelper helper, double expected, double heard, String what) {
        helper.assertTrue(Math.abs(expected - heard) < 1e-9, "Heard " + heard + " of a sound through " + what
                + ", expected " + expected);
    }
}
