package org.haruyq.createmediasystems.blocks.soundproof.client;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
import org.haruyq.createmediasystems.blocks.soundproof.AcousticPanelBlock;
import org.haruyq.createmediasystems.blocks.soundproof.SoundproofFeature;

import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The soundproofing Ponder scenes: walls (insulation sheets and acoustic panels), and the four doors. */
public final class SoundproofPonder {
    private SoundproofPonder() {
    }

    static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(SoundproofFeature.SHEET.getId(), SoundproofFeature.PANEL_ITEM.getId())
                .addStoryBoard(Create.asResource("steam_whistle"), SoundproofPonder::walls);
        helper.forComponents(SoundproofFeature.DOOR_ITEM.getId(), SoundproofFeature.GLASS_DOOR_ITEM.getId(),
                        SoundproofFeature.SLIDING_DOOR_ITEM.getId(), SoundproofFeature.DOUBLE_DOOR_ITEM.getId())
                .addStoryBoard(Create.asResource("steam_whistle"), SoundproofPonder::doors);
    }

    /** A speaker west of a three-high stone wall (x 2), a listener east of it. */
    private static void walls(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "soundproofing", "Soundproofing a Wall");
        // The borrowed schematic's hidden whistle, tank and campfire go.
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 4, 7, 4), Blocks.AIR.defaultBlockState(), false);
        BlockPos speaker = util.grid().at(0, 1, 2);
        scene.world().setBlock(speaker, AudioFeature.SPEAKER.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST), false);
        scene.world().setBlocks(util.select().fromTo(2, 1, 0, 2, 3, 4), Blocks.STONE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(speaker), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(2, 1, 0, 2, 3, 4), Direction.DOWN);
        scene.idle(15);
        Vec3 mouth = util.vector().centerOf(speaker).add(.5, 0, 0);
        BlockPos wall = util.grid().at(2, 1, 2);
        Vec3 face = util.vector().blockSurface(wall, Direction.WEST);

        notes(scene, mouth, 120);
        say(scene, 120, face, "Sound from this mod's speakers, instruments and players is quieter for each soundproofing layer on the straight line between it and the listener. A plain block is no such layer.");

        ItemStack sheet = new ItemStack(SoundproofFeature.SHEET.get());
        scene.overlay().showControls(face, Pointing.LEFT, 40).rightClick().withItem(sheet);
        scene.idle(10);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, wall,
                AABB.encapsulatingFullBlocks(util.grid().at(2, 1, 0), util.grid().at(2, 3, 4)), 120);
        say(scene, 120, face, "Right-click a full block with a Sound Insulation Sheet to put the sheet into it. The block looks the same, and it now lets through a quarter of the sound.");
        say(scene, 120, face, "Holding a sheet outlines the blocks nearby that have one. Right-click such a block with a sheet to take it out again; breaking the block drops it.");

        BlockState panel = SoundproofFeature.PANEL.get().defaultBlockState().setValue(AcousticPanelBlock.FACING, Direction.WEST);
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z <= 4; z++) {
                BlockPos at = util.grid().at(1, y, z);
                scene.world().setBlock(at, panel.setValue(AcousticPanelBlock.CROSS, (y + z & 1) == 1), false);
            }
        }
        scene.world().showSection(util.select().fromTo(1, 1, 0, 1, 3, 4), Direction.EAST);
        scene.idle(15);
        say(scene, 130, face, "An Acoustic Foam Panel goes on a wall, floor or ceiling, and is a layer too. Layers add up: through a panel and a sheeted block, a sixteenth gets through.");

        BlockPos turned = util.grid().at(1, 2, 2);
        Vec3 front = util.vector().blockSurface(turned, Direction.WEST);
        scene.overlay().showControls(front, Pointing.LEFT, 40).rightClick().withItem(AllItems.WRENCH.asStack());
        scene.idle(10);
        scene.world().modifyBlock(turned, state -> state.cycle(AcousticPanelBlock.CROSS), false);
        say(scene, 110, front, "Panels put side by side alternate their wedges. Right-click one with a Wrench to turn its wedges the other way.");

        notes(scene, mouth, 100);
        say(scene, 110, face, PonderPalette.GREEN, "Sound that goes around a soundproofed wall, through an opening or a plain block, is not soundproofed: close every way through.");
    }

    /** Doors in a white wall along z 2, seen from the north, their leaves on that side. */
    private static void doors(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "soundproof_doors", "Soundproof Doors");
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 4, 7, 4), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(0, 1, 2, 4, 3, 2), Blocks.WHITE_CONCRETE.defaultBlockState(), false);
        BlockPos normal = util.grid().at(1, 1, 2);
        BlockPos glass = util.grid().at(3, 1, 2);
        door(scene, normal, SoundproofFeature.DOOR.get(), DoorHingeSide.LEFT);
        door(scene, glass, SoundproofFeature.GLASS_DOOR.get(), DoorHingeSide.RIGHT);
        scene.world().showSection(util.select().fromTo(0, 1, 2, 4, 3, 2), Direction.DOWN);
        scene.idle(20);

        Vec3 normalFront = util.vector().blockSurface(normal.above(), Direction.NORTH);
        Vec3 glassFront = util.vector().blockSurface(glass.above(), Direction.NORTH);
        say(scene, 120, normalFront, "Soundproof doors are layers while closed: this mod's sounds that cross a closed door get through at a quarter. Open, they let it all through.");

        scene.overlay().showControls(normalFront, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        open(scene, normal, true);
        open(scene, glass, true);
        say(scene, 100, normalFront, "They open and close like an iron door does by redstone, and by hand as well.");
        open(scene, normal, false);
        open(scene, glass, false);
        scene.idle(20);
        say(scene, 100, glassFront, "The Soundproof Glass Door has a large frosted pane to see through, and soundproofs the same.");

        // The sliding door at x 0, running east before x 1; the double door at x 3 and 4, its hinges outside.
        scene.world().hideSection(util.select().fromTo(0, 1, 2, 4, 3, 2), Direction.UP);
        scene.idle(15);
        scene.world().setBlocks(util.select().fromTo(0, 1, 2, 4, 3, 2), Blocks.WHITE_CONCRETE.defaultBlockState(), false);
        BlockPos sliding = util.grid().at(0, 1, 2);
        BlockPos left = util.grid().at(4, 1, 2);
        BlockPos right = util.grid().at(3, 1, 2);
        door(scene, sliding, SoundproofFeature.SLIDING_DOOR.get(), DoorHingeSide.LEFT);
        door(scene, left, SoundproofFeature.DOUBLE_DOOR.get(), DoorHingeSide.LEFT);
        door(scene, right, SoundproofFeature.DOUBLE_DOOR.get(), DoorHingeSide.RIGHT);
        scene.world().showSection(util.select().fromTo(0, 1, 2, 4, 3, 2), Direction.DOWN);
        scene.idle(20);

        Vec3 slidingFront = util.vector().blockSurface(sliding.above(), Direction.NORTH);
        open(scene, sliding, true);
        scene.idle(10);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, sliding, new AABB(util.grid().at(1, 1, 1)).expandTowards(0, 1, 0), 120);
        say(scene, 120, slidingFront, "A Soundproof Sliding Door hangs on a rail in front of its wall and slides to its hinge side, in front of the block beside it. Keep the space in front of that block free.");
        open(scene, sliding, false);
        scene.idle(15);

        Vec3 meeting = util.vector().blockSurface(right.above(), Direction.NORTH).add(.4, 0, 0);
        scene.overlay().showControls(meeting, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        open(scene, left, true);
        open(scene, right, true);
        say(scene, 120, meeting, "A Soundproof Double Door puts both its leaves at once, and needs room for the second beside the first. Opening either leaf opens both.");
        open(scene, left, false);
        open(scene, right, false);
        scene.idle(20);
        say(scene, 120, slidingFront, PonderPalette.GREEN, "A door keeps sound in only as well as its wall does: sound through the plain blocks beside it is not soundproofed. Put Sound Insulation Sheets into them.");
    }

    private static void door(CreateSceneBuilder scene, BlockPos pos, Block block, DoorHingeSide hinge) {
        BlockState state = block.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.HINGE, hinge);
        scene.world().setBlock(pos, state, false);
        scene.world().setBlock(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), false);
    }

    private static void open(CreateSceneBuilder scene, BlockPos pos, boolean open) {
        scene.world().modifyBlock(pos, state -> state.setValue(DoorBlock.OPEN, open), false);
        scene.world().modifyBlock(pos.above(), state -> state.setValue(DoorBlock.OPEN, open), false);
        scene.idle(20);
    }
}
