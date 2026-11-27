package org.haruyq.createmediasystems.blocks.display;

import org.haruyq.createmediasystems.CreateMediaSystems;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateMediaSystems.MOD_ID)
@PrefixGameTestTemplate(false)
public class DisplayGameTests {
    @GameTest(template = "empty")
    public static void screenFillsItsCellsWithPartsAndGoesWithThem(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos anchor = test.absolutePos(new BlockPos(2, 2, 2));
        // Facing north, the screen grows to the west (its right as seen from the front) and up.
        BlockPos right = anchor.west();
        BlockPos up = anchor.above();
        BlockPos corner = anchor.west().above();
        BlockPos beyond = anchor.west(2);
        level.setBlockAndUpdate(anchor, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(up, Blocks.STONE.defaultBlockState());
        var display = (DisplayBlock.Entity) level.getBlockEntity(anchor);

        display.resize(40, 32, DisplayBlock.Ratio.FREE);
        test.assertTrue(isPart(test, right) && isPart(test, corner) && isPart(test, beyond),
                "a 2.5 by 2 screen fills every cell it reaches into");
        test.assertTrue(level.getBlockState(up).is(Blocks.STONE), "a filled cell stays as it is");
        test.assertTrue(level.getBlockState(beyond).getValue(DisplayBlock.CELL_WIDTH) == 8
                        && level.getBlockState(right).getValue(DisplayBlock.CELL_WIDTH) == 16,
                "a part at the screen's edge is cut to the half block the screen reaches into it");
        test.assertTrue(DisplayBlock.anchor(level, corner).equals(anchor), "a part leads to its screen");

        display.resize(32, 16, DisplayBlock.Ratio.FREE);
        test.assertTrue(isPart(test, right) && level.getBlockState(corner).isAir() && level.getBlockState(beyond).isAir(),
                "shrinking takes out the parts past the screen");
        test.assertTrue(level.getBlockState(up).is(Blocks.STONE), "shrinking leaves other blocks alone");

        display.resize(32, 32, DisplayBlock.Ratio.FREE);
        level.destroyBlock(corner, true);
        test.assertTrue(level.getBlockState(anchor).isAir() && level.getBlockState(right).isAir(),
                "breaking a part breaks the screen and its other parts");
        test.assertItemEntityCountIs(DisplayFeature.DISPLAY_ITEM.get().asItem(), new BlockPos(2, 2, 2), 2, 1);
        test.succeed();
    }

    /** Screens saved before they had parts get them once loaded, image viewers as displays. */
    @GameTest(template = "empty")
    public static void screenSavedWithoutPartsGetsThemOnLoad(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos display = test.absolutePos(new BlockPos(2, 2, 2));
        BlockPos viewer = test.absolutePos(new BlockPos(2, 2, 5));
        CompoundTag saved = new CompoundTag();
        saved.putInt("Width16", 48);
        saved.putInt("Height16", 24);
        for (BlockPos at : new BlockPos[] {display, viewer}) {
            var block = at == display ? DisplayFeature.DISPLAY.get() : DisplayFeature.IMAGE_VIEWER.get();
            level.setBlockAndUpdate(at, block.defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
            var screen = (ScreenEntity) level.getBlockEntity(at);
            screen.loadWithComponents(saved, level.registryAccess());
            // As when its chunk loads.
            screen.onLoad();
        }
        test.succeedWhen(() -> {
            for (BlockPos at : new BlockPos[] {display, viewer}) {
                var corner = level.getBlockState(at.west(2).above());
                test.assertTrue(corner.getBlock() == level.getBlockState(at).getBlock() && corner.getValue(DisplayBlock.PART)
                        && corner.getValue(DisplayBlock.CELL_HEIGHT) == 8, "a loaded 3 by 1.5 screen has its far corner part at " + at);
            }
        });
    }

    /** A bearing that turns a screen glued by its own block takes its parts along, and puts them all back. */
    @GameTest(template = "empty")
    public static void bearingCarriesTheWholeScreen(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos anchor = test.absolutePos(new BlockPos(4, 3, 3));
        BlockPos deck = anchor.below();
        BlockPos bearingPos = deck.below();
        level.setBlockAndUpdate(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(DirectionalBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(deck, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(anchor, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
        ((DisplayBlock.Entity) level.getBlockEntity(anchor)).resize(48, 32, DisplayBlock.Ratio.FREE);
        level.addFreshEntity(new SuperGlueEntity(level, new AABB(deck).deflate(.2).minmax(new AABB(anchor).deflate(.2))));

        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
        bearing.assemble();
        var carrier = bearing.getMovedContraption();
        test.assertTrue(carrier != null && carrier.getContraption().getBlocks().size() == 7,
                "the deck, the screen's block and its five parts go onto the bearing");
        test.assertTrue(level.getBlockState(anchor.west(2).above()).isAir(), "no part is left behind");
        bearing.disassemble();
        test.assertTrue(isPart(test, anchor.west(2).above()) && level.getBlockEntity(anchor) instanceof DisplayBlock.Entity,
                "the screen and its parts are back");
        test.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor).inflate(5)).isEmpty(),
                "moving the screen dropped nothing");
        test.succeed();
    }

    private static boolean isPart(GameTestHelper test, BlockPos pos) {
        var state = test.getLevel().getBlockState(pos);
        return state.is(DisplayFeature.DISPLAY.get()) && state.getValue(DisplayBlock.PART);
    }
}
