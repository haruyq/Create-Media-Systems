package org.haruyq.createmediasystems.screen;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.video.VideoFeature;
import org.haruyq.createmediasystems.blocks.video.VideoPlayerBlock;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The Media Server's and the Monitor's Ponder scene: a window shared to the monitor on top and the Blu-ray Player beside it. */
public final class ScreenPonder {
    private ScreenPonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ScreenFeature.SERVER_ITEM.getId(), ScreenFeature.MONITOR_ITEM.getId())
                .addStoryBoard(Create.asResource("redstone_link"), ScreenPonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "screen_share_server", "Sharing Your Screen");
        BlockPos server = util.grid().at(2, 1, 2);
        BlockPos player = util.grid().at(3, 1, 2);
        // Its front: its top is under the monitor.
        Vec3 top = util.vector().blockSurface(server, Direction.NORTH);

        scene.world().setBlock(server, ScreenFeature.SERVER.get().defaultBlockState()
                .setValue(ScreenServerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(server), Direction.DOWN);
        scene.idle(15);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, server, new AABB(server), 110);
        say(scene, 110, top, "The Media Server shares a window or a whole screen of your own computer with the other players, live.");

        BlockPos monitor = server.above();
        scene.world().setBlock(monitor, ScreenFeature.MONITOR.get().defaultBlockState()
                .setValue(ScreenMonitorBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(monitor), Direction.DOWN);
        scene.idle(15);
        say(scene, 120, util.vector().centerOf(monitor), "Place a Monitor on top of the server first: without one, right-clicking the server opens nothing. The monitor shows the share: to you as you capture it, to everyone else as it reaches them.");

        scene.world().setBlock(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        say(scene, 110, util.vector().topOf(player), "A Blu-ray Player right next to the server shows the share on its displays, as long as the player holds no disc of its own.");

        scene.overlay().showControls(top, Pointing.DOWN, 50).rightClick();
        scene.idle(10);
        say(scene, 130, top, "Right-click the server and pick an app's window or a whole screen. Set the resolution, the frame rate, the bitrate, where its sound comes from (the app, the whole computer, or a microphone) and how loud it goes out, up to 200%.");
        scene.world().modifyBlock(server, state -> state.setValue(ScreenServerBlock.LIT, true), false);
        scene.idle(10);
        say(scene, 120, util.vector().centerOf(player), PonderPalette.GREEN, "Once you go live, your computer sends it straight to every player watching, and the server shows you a preview of what they see.");

        say(scene, 110, top, "Until you stop sharing or leave, the server is yours. Screen sharing works on Windows.");

        scene.overlay().showControls(top, Pointing.DOWN, 50).withItem(AllItems.GOGGLES.asStack());
        say(scene, 110, top, "Through Engineer's Goggles the server shows whether it shares, and how each viewer's connection holds up.");
    }
}
