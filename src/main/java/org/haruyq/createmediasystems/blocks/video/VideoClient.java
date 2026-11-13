package org.haruyq.createmediasystems.blocks.video;

import static org.haruyq.createmediasystems.client.PonderScenes.plate;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.client.ClientFeature;
import org.haruyq.createmediasystems.disc.DiscFeature;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

public final class VideoClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        EqualizerFaders.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(VideoFeature.VIDEO_PLAYER.getId(), DiscFeature.DVD.getId(), DiscFeature.BLU_RAY.getId(), VideoFeature.REMOTE.getId())
                .addStoryBoard(Create.asResource("redstone_link"), VideoClient::bluRayPlayer);
        helper.forComponents(VideoFeature.EQUALIZER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), VideoClient::equalizer);
    }

    /** Moves an equalizer's fader by {@code steps} half-dB steps, one a tick. */
    private static void slide(CreateSceneBuilder scene, BlockPos equalizer, int fader, int steps) {
        for (int step = 0; step < Math.abs(steps); step++) {
            scene.world().modifyBlockEntity(equalizer, EqualizerBlock.Entity.class,
                    entity -> entity.set(fader, entity.gain(fader) + Math.signum(steps) * 0.5f));
            scene.idle(1);
        }
    }

    private static void bluRayPlayer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "blu_ray_player", "DVDs, Blu-rays and the Blu-ray Player");

        BlockPos player = util.grid().at(2, 1, 2);
        Vec3 inAir = util.vector().of(2.5, 3, 2.5);
        scene.overlay().showControls(inAir, Pointing.DOWN, 50).rightClick().withItem(new ItemStack(DiscFeature.DVD.get()));
        say(scene, 110, inAir, "Use a DVD or a Blu-ray in the air to write its playlist, one video link per row. The server checks every link before writing them.");
        scene.overlay().showControls(inAir, Pointing.DOWN, 50).rightClick().withItem(new ItemStack(DiscFeature.BLU_RAY.get()));
        say(scene, 110, inAir, "A DVD holds 16 videos played at up to 480p, and a Blu-ray 64 at up to 1080p. Neither plays in a jukebox.");

        scene.world().setBlock(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(DiscFeature.BLU_RAY.get()));
        say(scene, 110, util.vector().topOf(player), PonderPalette.GREEN, "Right-click a Blu-ray Player with either disc to play its videos one after another; it stops after the last. Right-click with an empty hand to take the disc out.");

        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 50).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        say(scene, 100, util.vector().topOf(player), "Its volume is set like any Create value: hold right-click on its top with a Wrench. It turns its displays and its speakers up or down alike. Its front sets how brightly its displays light their surroundings, the same for everyone; 0% turns it off.");

        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 50).rightClick().whileSneaking()
                .withItem(new ItemStack(VideoFeature.REMOTE.get()));
        say(scene, 110, util.vector().centerOf(player), "Shift+right-click it with a Remote to pair them. Use the Remote in the air for its buttons: previous, play/pause, stop, next, play a video by number, and volume.");
        scene.overlay().showText(90)
                .pointAt(util.vector().centerOf(player))
                .placeNearTarget()
                .text("A Remote reaches its Blu-ray Player only within the server's range (64 blocks unless set otherwise).");
        scene.idle(100);

        say(scene, 90, util.vector().centerOf(player), "Through Engineer's Goggles it shows which video it plays, and how loud.");
    }

    private static void equalizer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "equalizer", "Equalizers");

        BlockPos player = util.grid().at(3, 1, 2);
        scene.world().setBlock(player, VideoFeature.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(10);
        BlockState facingCamera = VideoFeature.EQUALIZER.get().defaultBlockState()
                .setValue(EqualizerBlock.FACING, Direction.NORTH);
        BlockPos first = player.west();
        scene.world().setBlock(first, facingCamera, false);
        scene.world().showSection(util.select().position(first), Direction.DOWN);
        scene.idle(15);
        say(scene, 100, util.vector().topOf(first), "An Equalizer touching a Blu-ray Player turns the Low, Mid and Hi of its sound up or down, on its displays and speakers alike.");

        // Its faders from the left as one faces it (east here): Low, Mid, Hi.
        Vec3 low = util.vector().topOf(first).add(5 / 16.0, 0, 0);
        Vec3 hi = util.vector().topOf(first).add(-5 / 16.0, 0, 0);
        scene.overlay().showControls(low, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        slide(scene, first, 0, 12);
        scene.overlay().showText(130)
                .attachKeyFrame()
                .colored(PonderPalette.GREEN)
                .pointAt(low)
                .placeNearTarget()
                .text("Point at a fader, hold right-click and look along it: the knob follows, showing you its gain, and everyone hears it once you let go. Each goes up or down by up to 12 dB.");
        scene.idle(30);
        scene.overlay().showControls(hi, Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        slide(scene, first, 2, -8);
        scene.idle(90);

        BlockPos second = first.west();
        scene.world().setBlock(second, facingCamera, false);
        scene.world().showSection(util.select().position(second), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(120)
                .attachKeyFrame()
                .pointAt(util.vector().topOf(second))
                .placeNearTarget()
                .text("A second Equalizer touching the first splits each band in two and takes the lower half: BASS, Low-Mid and Hi-Mid. More than two do nothing.");
        scene.idle(20);
        slide(scene, second, 0, 8);
        scene.idle(100);

        say(scene, 100, util.vector().centerOf(first), "Sneak to place a block on its top instead. Through Engineer's Goggles it shows each band's gain.");
    }
}
