package org.haruyq.createmediasystems.blocks.display;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.lighting.LightingLinkItem;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
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

    /**
     * The Tuning Wrench on a display: with a console picked it puts that down, then picks a player and links any
     * part's display to it; Shift unlinks. Lighting is the display's own, and one saved before has it for both.
     */
    @GameTest(template = "empty")
    public static void wrenchLinksAndLightsDisplays(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos anchor = test.absolutePos(new BlockPos(3, 2, 3));
        BlockPos source = test.absolutePos(new BlockPos(0, 2, 0));
        level.setBlockAndUpdate(anchor, DisplayFeature.DISPLAY.get().defaultBlockState().setValue(DisplayBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(source, VideoFeature.VIDEO_PLAYER.get().defaultBlockState());
        var display = (DisplayBlock.Entity) level.getBlockEntity(anchor);
        display.resize(32, 16, DisplayBlock.Ratio.FREE);
        ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.setPos(Vec3.atCenterOf(anchor.north(2)));
        ItemStack wrench = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        wrench.set(LightingFeature.LIGHTING_TARGET.get(), GlobalPos.of(level.dimension(), anchor.east(6)));
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        try {
            LightingLinkItem.link(player, anchor);
            test.assertTrue(!wrench.has(LightingFeature.LIGHTING_TARGET.get()), "a display clicked with a console picked puts it down");
            LightingLinkItem.link(player, source);
            test.assertTrue(source.equals(DisplayWrench.picked(wrench, level)), "then a click on the player picks it");
            LightingLinkItem.link(player, anchor.west());
            test.assertTrue(display.source().filter(source::equals).isPresent(), "a click on a part links its display");
            player.setShiftKeyDown(true);
            LightingLinkItem.link(player, anchor);
            test.assertTrue(display.source().isEmpty(), "Shift+right-click unlinks it");
            player.setShiftKeyDown(false);

            DisplayWrench.lightDisplay(player, new DisplayWrench.LightDisplay(anchor, 30, 150));
            test.assertTrue(display.cinematic() == 0.3f && display.ambient() == 1,
                    "the panel sets both, kept within 0..100%: " + display.cinematic() + ", " + display.ambient());
            CompoundTag saved = display.saveWithFullMetadata(level.registryAccess());
            saved.remove("Cinematic");
            saved.remove("Ambient");
            saved.putInt("Lighting", 40);
            display.loadWithComponents(saved, level.registryAccess());
            test.assertTrue(display.cinematic() == 0.4f && display.ambient() == 0.4f,
                    "a display saved with one lighting loads it as both: " + display.cinematic() + ", " + display.ambient());
        } finally {
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        test.succeed();
    }

    private static boolean isPart(GameTestHelper test, BlockPos pos) {
        var state = test.getLevel().getBlockState(pos);
        return state.is(DisplayFeature.DISPLAY.get()) && state.getValue(DisplayBlock.PART);
    }
}
