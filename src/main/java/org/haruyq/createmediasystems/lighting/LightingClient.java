package org.haruyq.createmediasystems.lighting;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

/** Stage lighting on this client: the analysis it plays, the console's controls, the renderer, and the console and stage light scenes. */
public final class LightingClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        AnalysisPlayback.register();
        LightingControls.register();
        LightingRenderer.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(LightingFeature.LIGHTING_CONSOLE.getId(), LightingFeature.LIGHTING_MONITOR.getId())
                .addStoryBoard(Create.asResource("redstone_link"), LightingClient::lightingConsole);
        helper.forComponents(LightingFeature.STAGE_LIGHT.getId(), LightingFeature.LASER_PROJECTOR.getId(),
                        LightingFeature.LIGHTING_LINK.getId())
                .addStoryBoard(Create.asResource("redstone_link"), LightingClient::stageLights);
    }

    /**
     * Places a lighting console facing the camera with its east front desk at {@code origin}, already formed when
     * {@code formed}; else desk by desk, then its monitors, forming as the last one goes on.
     */
    private static void console(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos origin, boolean formed) {
        Direction facing = Direction.NORTH;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos desk = LightingConsoleBlock.at(origin, facing, x, 0, z);
                scene.world().setBlock(desk, LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState()
                        .setValue(LightingConsoleBlock.FACING, facing).setValue(LightingConsoleBlock.COLUMN, x)
                        .setValue(LightingConsoleBlock.BACK, z == 1), false);
                if (!formed) {
                    scene.world().showSection(util.select().position(desk), Direction.DOWN);
                    scene.idle(3);
                }
            }
        }
        if (!formed) {
            scene.idle(10);
        }
        for (int x = 0; x < 4; x++) {
            // Clears what Create's schematic has above the front desks, so showing the console shows only it.
            scene.world().setBlock(LightingConsoleBlock.at(origin, facing, x, 1, 0), Blocks.AIR.defaultBlockState(), false);
            BlockPos monitor = LightingConsoleBlock.at(origin, facing, x, 1, 1);
            scene.world().setBlock(monitor, LightingFeature.LIGHTING_MONITOR.get().defaultBlockState()
                    .setValue(LightingMonitorBlock.FACING, facing).setValue(LightingMonitorBlock.COLUMN, x), false);
            if (!formed) {
                scene.world().showSection(util.select().position(monitor), Direction.DOWN);
                scene.idle(5);
            }
        }
        Selection whole = util.select().fromTo(origin, LightingConsoleBlock.at(origin, facing, 3, 1, 1));
        scene.world().modifyBlocks(whole, state -> state.hasProperty(LightingConsoleBlock.FORMED)
                ? state.setValue(LightingConsoleBlock.FORMED, true) : state, false);
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                // The ponder level keeps a block entity's old state when its block changes; the console reads it.
                scene.world().modifyBlockEntity(LightingConsoleBlock.at(origin, facing, x, 0, z), LightingConsoleBlock.Entity.class,
                        entity -> {
                            entity.setBlockState(entity.getLevel().getBlockState(entity.getBlockPos()));
                            entity.formed = true;
                        });
            }
        }
        if (formed) {
            scene.world().showSection(whole, Direction.DOWN);
        }
    }

    private static void lightingConsole(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "lighting_console", "The Lighting Console");

        BlockPos origin = util.grid().at(4, 1, 1);
        BlockPos back = LightingConsoleBlock.at(origin, Direction.NORTH, 2, 1, 1);
        console(scene, util, origin, false);
        scene.effects().indicateSuccess(back);
        AABB whole = new AABB(1, 1, 1, 5, 3, 3);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, origin, whole, 90);
        say(scene, 110, util.vector().topOf(back), PonderPalette.GREEN, "A Lighting Console is 4 desks wide and 2 deep, all facing the same way, with a Lighting Console Monitor on each desk of the back row. It comes together as soon as the last block is in place.");

        Vec3 deck = util.vector().of(3, 2, 1.8);
        scene.overlay().showControls(deck, Pointing.DOWN, 40).rightClick();
        say(scene, 110, deck, "Point at its controls with an empty hand or a Wrench: right-click buttons and pads, scroll over a value to change it, and hold right-click to move a fader or the aim pad.");

        say(scene, 120, util.vector().topOf(back), "Its faders set each of the six groups' brightness and the master. The rest, on the desk and the monitors, set the selected group's colours, presets, motion, aim, and how it follows sound and video.");

        say(scene, 90, util.vector().topOf(origin), "Through Engineer's Goggles it shows the selected group and how many fixtures are linked.");
    }

    private static void stageLights(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "stage_lights", "Stage Lights and the LED Tuning Wrench");

        BlockPos origin = util.grid().at(4, 1, 3);
        BlockPos head = util.grid().at(3, 1, 1);
        BlockPos laser = util.grid().at(1, 1, 1);
        BlockPos player = util.grid().at(0, 1, 4);
        scene.world().setBlock(head, LightingFeature.STAGE_LIGHT.get().defaultBlockState()
                .setValue(StageLightBlock.FACING, Direction.NORTH), false);
        scene.world().setBlock(laser, LightingFeature.LASER_PROJECTOR.get().defaultBlockState()
                .setValue(StageLightBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(head), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(laser), Direction.DOWN);
        scene.idle(10);
        say(scene, 90, util.vector().topOf(head), "Moving Head Lights and Laser Projectors light up the stage. A Lighting Console drives up to 64 of them, in six groups.");

        console(scene, util, origin, true);
        scene.idle(15);
        ItemStack link = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        BlockPos desk = LightingConsoleBlock.at(origin, Direction.NORTH, 1, 0, 0);
        scene.overlay().showControls(util.vector().topOf(desk), Pointing.DOWN, 40).rightClick().withItem(link);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, origin, new AABB(1, 1, 3, 5, 3, 5), 260);
        say(scene, 90, util.vector().topOf(desk), "Hold a LED Tuning Wrench and right-click a finished console to pick it.");

        scene.overlay().showControls(util.vector().topOf(head), Pointing.DOWN, 30).rightClick().withItem(link);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, head, new AABB(head), 160);
        scene.idle(35);
        scene.overlay().showControls(util.vector().topOf(laser), Pointing.DOWN, 30).rightClick().withItem(link);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, laser, new AABB(laser), 125);
        say(scene, 110, util.vector().topOf(laser), PonderPalette.GREEN, "Then right-click fixtures to link them to the group selected on the console. Right-click one again, or Shift+right-click it, to unlink it.");

        scene.world().setBlock(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 40).rightClick().withItem(link);
        say(scene, 120, util.vector().topOf(player), "Right-click a Blu-ray Player, a Disc Reader or a jukebox to link it too. Turn on sound or video linking on the console, and the lights follow its music or its picture's colours. Shift+right-click a Blu-ray Player to link only its picture.");

        scene.overlay().showControls(util.vector().topOf(head), Pointing.DOWN, 40).scroll();
        say(scene, 110, util.vector().topOf(head), "Looking at a fixture with an empty hand, scroll to move it to another group. Shift+scroll changes its place in the group's order, and Ctrl+scroll the colour it takes from the group.");

        say(scene, 110, util.vector().topOf(laser), "Right-click a player with the LED Tuning Wrench to have the selected group follow them, or Shift+right-click the air to be followed yourself.");

        say(scene, 80, util.vector().topOf(head), "Place a fixture under a ceiling to hang it upside down. Through Engineer's Goggles it shows its group and order.");
    }
}
