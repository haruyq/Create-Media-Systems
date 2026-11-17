package org.haruyq.createmediasystems.disc;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.blocks.audio.AudioFeature;
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

/** The Disc Drive Emulator's Ponder scene: a real disc played by the Blu-ray Player or Disc Reader beside it. */
public final class DrivePonder {
    private DrivePonder() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(DiscFeature.DRIVE_EMULATOR_ITEM.getId())
                .addStoryBoard(Create.asResource("redstone_link"), DrivePonder::scene);
    }

    private static void scene(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "disc_drive_emulator", "Playing Discs from Your Computer's Drive");
        BlockPos emulator = util.grid().at(2, 1, 2);
        BlockPos player = util.grid().at(3, 1, 2);
        BlockPos reader = util.grid().at(1, 1, 2);
        Vec3 top = util.vector().topOf(emulator);

        scene.world().setBlock(emulator, DiscFeature.DRIVE_EMULATOR.get().defaultBlockState()
                .setValue(DriveEmulatorBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(emulator), Direction.DOWN);
        scene.idle(15);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, emulator, new AABB(emulator), 110);
        say(scene, 110, top, "The Disc Drive Emulator stands for the optical drive of your own computer: a Blu-ray, DVD or audio CD in that real drive plays in the game.");

        scene.world().setBlock(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        say(scene, 110, util.vector().topOf(player), "A Blu-ray Player right next to the emulator plays a DVD or Blu-ray from the drive, as long as the player holds no disc of its own.");

        scene.overlay().showControls(top, Pointing.DOWN, 50).rightClick();
        scene.idle(10);
        say(scene, 120, top, "Right-click the emulator to open its screen, then put a disc in your computer's drive. The emulator's tray closes on it, and the disc is read.");
        scene.world().modifyBlock(emulator, state -> state.setValue(DriveEmulatorBlock.LIT, true), false);
        scene.idle(10);
        say(scene, 120, util.vector().centerOf(player), PonderPalette.GREEN, "Once read, the disc is shared straight from your computer to the other players, and the Blu-ray Player beside the emulator plays it.");

        say(scene, 130, top, "Until you take the disc out or leave, the emulator is yours. On its screen you choose the disc's audio and subtitle tracks, where it has any to choose from, and the eject button takes the disc out.");

        scene.world().setBlock(reader, AudioFeature.DISC_READER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(reader), Direction.DOWN);
        scene.idle(15);
        say(scene, 100, util.vector().topOf(reader), "An audio CD plays with a Disc Reader beside the emulator instead.");

        scene.overlay().showControls(top, Pointing.DOWN, 50).withItem(AllItems.GOGGLES.asStack());
        say(scene, 110, top, "Through Engineer's Goggles the emulator shows its state, and while it shares a disc, how each viewer's connection holds up.");
    }
}
