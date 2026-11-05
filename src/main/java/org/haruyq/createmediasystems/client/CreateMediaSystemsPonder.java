package org.haruyq.createmediasystems.client;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.display.ImageViewerBlock;
import org.haruyq.createmediasystems.train.TrainSignalReceiverBlock;
import org.haruyq.createmediasystems.video.EqualizerBlock;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.station.StationBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scenes (hold W over the item). They borrow Create's own schematics for the base plate and place CMS
 * blocks into them, so no structure files are needed. English text is here; translations are in the lang files
 * under {@code create_media_systems.ponder.<scene>.*}.
 */
public class CreateMediaSystemsPonder implements PonderPlugin {
    @Override
    public String getModId() {
        return CreateMediaSystems.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(CreateMediaSystems.CUSTOM_DISC.getId(), CreateMediaSystems.RANGE_QUILL.getId())
                .addStoryBoard(Create.asResource("redstone_link"), CreateMediaSystemsPonder::jukebox);
        helper.forComponents(CreateMediaSystems.SPEAKER.getId(), CreateMediaSystems.DISC_READER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), CreateMediaSystemsPonder::speakers);
        helper.forComponents(CreateMediaSystems.TRAIN_DISC_READER.getId(), CreateMediaSystems.TRAIN_SPEAKER.getId(), CreateMediaSystems.ANNOUNCEMENT_SCHEDULE.getId())
                .addStoryBoard(Create.asResource("train_controls"), CreateMediaSystemsPonder::trainDiscReader);
        helper.forComponents(CreateMediaSystems.TRAIN_DETECTOR.getId(), CreateMediaSystems.TRAIN_SIGNAL_RECEIVER.getId())
                .addStoryBoard(Create.asResource("train_observer"), CreateMediaSystemsPonder::trainDetector);
        helper.forComponents(CreateMediaSystems.VIDEO_PLAYER.getId(), CreateMediaSystems.DVD.getId(), CreateMediaSystems.BLU_RAY.getId(), CreateMediaSystems.REMOTE.getId())
                .addStoryBoard(Create.asResource("redstone_link"), CreateMediaSystemsPonder::bluRayPlayer);
        helper.forComponents(CreateMediaSystems.DISPLAY.getId(), CreateMediaSystems.DISPLAY_QUILL.getId())
                .addStoryBoard(Create.asResource("redstone_link"), CreateMediaSystemsPonder::display);
        helper.forComponents(CreateMediaSystems.EQUALIZER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), CreateMediaSystemsPonder::equalizer);
        helper.forComponents(CreateMediaSystems.IMAGE_VIEWER.getId())
                .addStoryBoard(Create.asResource("redstone_link"), CreateMediaSystemsPonder::imageViewer);
    }

    private static void notes(CreateSceneBuilder scene, Vec3 at, int cycles) {
        scene.effects().emitParticles(at, scene.effects().simpleParticleEmitter(ParticleTypes.NOTE, Vec3.ZERO), 1, cycles);
    }

    private static void jukebox(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "jukebox", "Streaming Custom Discs");

        BlockPos jukebox = util.grid().at(2, 1, 2);
        scene.world().setBlock(jukebox, Blocks.JUKEBOX.defaultBlockState(), false);
        scene.world().showSection(util.select().position(jukebox), Direction.DOWN);
        scene.idle(15);

        ItemStack disc = new ItemStack(CreateMediaSystems.CUSTOM_DISC.get());
        say(scene, 80, util.vector().topOf(jukebox), "Use a Custom Disc in the air to write a YouTube or Discord link onto it. The server checks that the link plays before writing it.");

        scene.overlay().showControls(util.vector().topOf(jukebox), Pointing.DOWN, 40).rightClick().withItem(disc);
        scene.idle(10);
        scene.world().setBlock(jukebox, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true), false);
        notes(scene, util.vector().topOf(jukebox).add(0, 0.3, 0), 60);
        say(scene, 90, util.vector().topOf(jukebox), PonderPalette.GREEN, "In any jukebox it streams to everyone nearby: loud up close, fading out with distance.");

        ItemStack quill = new ItemStack(CreateMediaSystems.RANGE_QUILL.get());
        scene.overlay().showControls(util.vector().topOf(jukebox), Pointing.DOWN, 40).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, jukebox, new AABB(jukebox), 200);
        say(scene, 80, util.vector().centerOf(jukebox), "To limit who hears it, right-click the jukebox with a Jukebox Range Quill to pick it.");

        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, "range",
                new AABB(Vec3.ZERO, new Vec3(5, 3, 5)), 120);
        say(scene, 110, util.vector().of(5, 2, 0), PonderPalette.GREEN, "Then select two corners like with the Schematic and Quill, and right-click again to set the volume. Only players inside the range hear it.");

        say(scene, 90, util.vector().centerOf(jukebox), "Right-click the picked jukebox again without a selection to change just the volume, or reset the range.");
    }

    private static void speakers(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "speakers", "Speakers and the Disc Reader");

        BlockPos reader = util.grid().at(2, 1, 1);
        BlockPos left = util.grid().at(3, 1, 3);
        BlockPos right = util.grid().at(1, 1, 3);
        scene.world().setBlock(reader, CreateMediaSystems.DISC_READER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(reader), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(CreateMediaSystems.CUSTOM_DISC.get()));
        say(scene, 80, util.vector().topOf(reader), "A Disc Reader plays a Custom Disc without making a sound itself...");

        var facingCamera = CreateMediaSystems.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, Direction.NORTH);
        scene.world().setBlock(left, facingCamera, false);
        scene.world().setBlock(right, facingCamera, false);
        scene.world().showSection(util.select().position(left), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(right), Direction.DOWN);
        scene.idle(10);
        say(scene, 80, util.vector().centerOf(left), "...for the Speakers linked to it. Speakers also work with a jukebox, playing in sync with it.");

        ItemStack quill = new ItemStack(CreateMediaSystems.RANGE_QUILL.get());
        scene.overlay().showControls(util.vector().topOf(reader), Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, reader, new AABB(reader), 120);
        scene.idle(35);
        scene.overlay().showControls(util.vector().topOf(left), Pointing.DOWN, 30).rightClick().withItem(quill);
        say(scene, 100, util.vector().centerOf(left), "With the Range Quill, pick the source, then right-click each speaker to link it and set its volume and channel.");

        Vec3 leftMouth = SpeakerBlock.mouth(left, Direction.NORTH);
        Vec3 rightMouth = SpeakerBlock.mouth(right, Direction.NORTH);
        notes(scene, leftMouth, 80);
        notes(scene, rightMouth, 80);
        scene.overlay().showText(40).pointAt(leftMouth).placeNearTarget().text("Left");
        scene.overlay().showText(40).pointAt(rightMouth).placeNearTarget().text("Right");
        scene.idle(50);
        say(scene, 90, util.vector().centerOf(right), PonderPalette.GREEN, "Set one to Left and one to Right for stereo, or leave them on Mix.");

        scene.overlay().showControls(util.vector().topOf(right), Pointing.DOWN, 30).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(10);
        scene.world().setBlock(right, facingCamera.setValue(SpeakerBlock.FACING, Direction.WEST), false);
        say(scene, 90, util.vector().centerOf(right), "Turn a speaker with a Wrench. It is loudest in front and quiet behind.");
    }

    private static void trainDetector(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("train_detector", "Signalling Trains Anywhere");
        scene.configureBasePlate(0, 0, 12);
        scene.scaleSceneView(.8f);
        scene.showBasePlate();
        scene.idle(5);
        for (int x = 0; x <= 10; x++) {
            scene.world().showSection(util.select().position(x, 1, 7), Direction.DOWN);
            scene.idle(1);
        }

        BlockPos detector = util.grid().at(5, 1, 4);
        BlockPos receiver = util.grid().at(1, 1, 1);
        BlockPos lamp = util.grid().at(2, 1, 1);
        scene.world().setBlock(detector, CreateMediaSystems.TRAIN_DETECTOR.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(detector), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(util.vector().topOf(util.grid().at(5, 1, 7)), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(CreateMediaSystems.TRAIN_DETECTOR_ITEM.get()));
        say(scene, 80, util.vector().centerOf(detector), "Place a Train Detector like a Train Observer: right-click the track first, then place it nearby.");

        scene.world().setBlock(receiver, CreateMediaSystems.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState(), false);
        scene.world().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(receiver, lamp), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(util.vector().topOf(receiver), Pointing.DOWN, 40).rightClick();
        say(scene, 90, util.vector().centerOf(receiver), "Give the detector and a Train Signal Receiver the same channel (empty hand). Receivers can be anywhere.");

        Selection train = util.select().fromTo(3, 2, 6, 7, 3, 8);
        ElementLink<WorldSectionElement> trainElement = scene.world().showIndependentSection(train, Direction.DOWN);
        scene.world().moveSection(trainElement, util.vector().of(-6, 0, 0), 0);
        scene.idle(10);
        scene.world().moveSection(trainElement, util.vector().of(12, 0, 0), 80);
        scene.world().animateBogey(util.grid().at(5, 2, 7), -12f, 80);
        scene.idle(30);
        scene.world().setBlock(receiver, CreateMediaSystems.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState()
                .setValue(TrainSignalReceiverBlock.POWERED, true), false);
        scene.world().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState()
                .setValue(RedstoneLampBlock.LIT, true), false);
        scene.effects().indicateRedstone(receiver);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(receiver))
                .placeNearTarget()
                .text("While a train is over the detector, every receiver on its channel gives a redstone signal of 15. Distance and unloaded chunks don't matter.");
        scene.idle(30);
        scene.world().setBlock(receiver, CreateMediaSystems.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState(), false);
        scene.world().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), false);
        scene.idle(70);
    }

    private static void trainDiscReader(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("train_disc_reader", "Announcements on Trains");
        scene.configureBasePlate(1, 0, 9);
        scene.scaleSceneView(.75f);
        scene.setSceneOffsetY(-1);
        scene.showBasePlate();
        for (int i = 10; i >= 0; i--) {
            scene.world().showSection(util.select().position(i, 1, 4), Direction.DOWN);
            scene.idle(1);
        }

        BlockPos stationPos = util.grid().at(4, 1, 1);
        BlockPos jukeboxPos = util.grid().at(3, 3, 4);
        Selection jukebox = util.select().position(jukeboxPos);
        BlockPos speakerPos = util.grid().at(5, 3, 4);
        Selection speaker = util.select().position(speakerPos);
        Selection train = util.select().fromTo(5, 2, 3, 1, 3, 5);
        scene.world().setBlock(jukeboxPos, Blocks.AIR.defaultBlockState(), false);
        scene.world().showSection(util.select().position(stationPos), Direction.DOWN);
        scene.idle(10);
        ElementLink<WorldSectionElement> trainElement = scene.world().showIndependentSection(train.substract(jukebox), Direction.DOWN);
        scene.world().cycleBlockProperty(stationPos, StationBlock.ASSEMBLING);
        scene.idle(15);

        ItemStack schedule = new ItemStack(CreateMediaSystems.ANNOUNCEMENT_SCHEDULE.get());
        say(scene, 100, util.vector().centerOf(jukeboxPos), "An Announcement Schedule is edited like a Train Schedule: add a station, then announcements for the trip there, each with its own link: on departure, after N seconds, at N%% of the way, N seconds before arrival, or on arrival.");

        scene.world().setBlock(jukeboxPos, CreateMediaSystems.TRAIN_DISC_READER.get().defaultBlockState(), false);
        scene.world().showSectionAndMerge(jukebox, Direction.DOWN, trainElement);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(jukeboxPos), Pointing.DOWN, 50).rightClick().withItem(schedule);
        say(scene, 90, util.vector().centerOf(jukeboxPos), "Put it in a Train Disc Reader on the carriage. It can be swapped even after the train is assembled.");

        scene.world().setBlock(speakerPos,
                CreateMediaSystems.TRAIN_SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACING, Direction.WEST), false);
        scene.world().showSectionAndMerge(speaker, Direction.DOWN, trainElement);
        scene.idle(15);
        say(scene, 90, util.vector().centerOf(speakerPos), "Train Speakers on the same train play it, without linking. Each is heard only in its own carriage.");

        scene.overlay().showControls(util.vector().topOf(jukeboxPos), Pointing.DOWN, 50).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        say(scene, 80, util.vector().centerOf(jukeboxPos), "To set their volume, hold right-click on the reader with a Wrench while the train is disassembled.");

        scene.world().cycleBlockProperty(stationPos, StationBlock.ASSEMBLING);
        scene.effects().indicateSuccess(stationPos);
        scene.world().animateTrainStation(stationPos, true);
        scene.idle(15);
        scene.world().moveSection(trainElement, util.vector().of(3, 0, 0), 40);
        scene.world().animateBogey(util.grid().at(3, 2, 4), -3f, 40);
        scene.idle(20);
        notes(scene, util.vector().topOf(speakerPos).add(3, 0.3, 0), 40);
        say(scene, 110, util.vector().centerOf(speakerPos).add(3, 0, 0), PonderPalette.GREEN, "When the train departs for or arrives at a matching station, the announcement plays, fading in and out as riders get on and off. Several readers play at once.");
    }

    private static void bluRayPlayer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "blu_ray_player", "DVDs, Blu-rays and the Blu-ray Player");

        BlockPos player = util.grid().at(2, 1, 2);
        Vec3 inAir = util.vector().of(2.5, 3, 2.5);
        scene.overlay().showControls(inAir, Pointing.DOWN, 50).rightClick().withItem(new ItemStack(CreateMediaSystems.DVD.get()));
        say(scene, 110, inAir, "Use a DVD or a Blu-ray in the air to write its playlist, one video link per row. The server checks every link before writing them.");
        scene.overlay().showControls(inAir, Pointing.DOWN, 50).rightClick().withItem(new ItemStack(CreateMediaSystems.BLU_RAY.get()));
        say(scene, 110, inAir, "A DVD holds 16 videos played at up to 480p, and a Blu-ray 64 at up to 1080p. Neither plays in a jukebox.");

        scene.world().setBlock(player, CreateMediaSystems.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(CreateMediaSystems.BLU_RAY.get()));
        say(scene, 110, util.vector().topOf(player), PonderPalette.GREEN, "Right-click a Blu-ray Player with either disc to play its videos one after another; it stops after the last. Right-click with an empty hand to take the disc out.");

        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 50).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        say(scene, 100, util.vector().topOf(player), "Its volume is set like any Create value: hold right-click on its top with a Wrench. It turns its displays and its speakers up or down alike. Its front sets how brightly its displays light their surroundings, the same for everyone; 0% turns it off.");

        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 50).rightClick().whileSneaking()
                .withItem(new ItemStack(CreateMediaSystems.REMOTE.get()));
        say(scene, 110, util.vector().centerOf(player), "Shift+right-click it with a Remote to pair them. Use the Remote in the air for its buttons: previous, play/pause, stop, next, play a video by number, and volume.");
        scene.overlay().showText(90)
                .pointAt(util.vector().centerOf(player))
                .placeNearTarget()
                .text("A Remote reaches its Blu-ray Player only within the server's range (64 blocks unless set otherwise).");
        scene.idle(100);

        say(scene, 90, util.vector().centerOf(player), "Through Engineer's Goggles it shows which video it plays, and how loud.");
    }

    private static void display(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "display", "Displays");

        // The camera looks from the north-west: the wall stands at the back, the display on its north side, so the
        // picture grows west (its right), towards the camera's side of the plate.
        Selection wall = util.select().fromTo(0, 1, 3, 4, 3, 3);
        scene.world().setBlocks(wall, Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().showSection(wall, Direction.DOWN);
        scene.idle(10);
        BlockPos display = util.grid().at(3, 1, 2);
        Vec3 picture = util.vector().of(2.5, 1.85, 2.9);
        scene.world().setBlock(display, CreateMediaSystems.DISPLAY.get().defaultBlockState()
                .setValue(DisplayBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(display), Direction.DOWN);
        scene.idle(15);
        say(scene, 90, util.vector().centerOf(display), "A Display goes on the side of a wall, where it shows a Blu-ray Player's video.");

        BlockPos player = util.grid().at(0, 1, 0);
        scene.world().setBlock(player, CreateMediaSystems.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(15);
        ItemStack quill = new ItemStack(CreateMediaSystems.DISPLAY_QUILL.get());
        scene.overlay().showControls(util.vector().topOf(player), Pointing.DOWN, 30).rightClick().withItem(quill);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, player, new AABB(player), 140);
        scene.idle(35);
        scene.overlay().showControls(util.vector().centerOf(display), Pointing.DOWN, 30).rightClick().withItem(quill);
        say(scene, 110, util.vector().centerOf(display), PonderPalette.GREEN, "Hold a Display Quill, right-click a Blu-ray Player to pick it, then right-click displays to link them; again to unlink. Any number of displays can show one player.");

        scene.overlay().showControls(util.vector().centerOf(display), Pointing.DOWN, 40).scroll().whileCTRL()
                .withItem(quill);
        scene.idle(10);
        scene.world().modifyBlockEntity(display, DisplayBlock.Entity.class, entity -> entity.resize(3 * DisplayBlock.UNIT,
                (int) Math.round(3 * DisplayBlock.UNIT / DisplayBlock.Ratio.WIDE.value), DisplayBlock.Ratio.WIDE));
        say(scene, 110, picture, "Looking at a display with the quill, Ctrl+scroll makes it a block wider or narrower (a sixteenth with Shift). It grows right and up, and its height follows its ratio.");

        scene.overlay().showControls(util.vector().centerOf(display), Pointing.DOWN, 40).rightClick().whileSneaking()
                .withItem(quill);
        say(scene, 110, picture, "Shift+right-click steps through the ratios: free, 16:9, 4:3, 21:9, 1:1, and the video's own. Alt+scroll sets the height alone.");

        say(scene, 110, picture, PonderPalette.GREEN, "Its sound comes from the middle of the picture, at the player's volume. While a speaker plays that Blu-ray Player, only the speakers are heard.");
    }

    /** Moves an equalizer's fader by {@code steps} half-dB steps, one a tick. */
    private static void slide(CreateSceneBuilder scene, BlockPos equalizer, int fader, int steps) {
        for (int step = 0; step < Math.abs(steps); step++) {
            scene.world().modifyBlockEntity(equalizer, EqualizerBlock.Entity.class,
                    entity -> entity.set(fader, entity.gain(fader) + Math.signum(steps) * 0.5f));
            scene.idle(1);
        }
    }

    private static void equalizer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "equalizer", "Equalizers");

        BlockPos player = util.grid().at(3, 1, 2);
        scene.world().setBlock(player, CreateMediaSystems.VIDEO_PLAYER.get().defaultBlockState()
                .setValue(VideoPlayerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(player), Direction.DOWN);
        scene.idle(10);
        BlockState facingCamera = CreateMediaSystems.EQUALIZER.get().defaultBlockState()
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

    private static void imageViewer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = plate(builder, "image_viewer", "Image Viewers");

        // Set up as in the display's scene: the picture grows west, towards the camera's side of the plate.
        Selection wall = util.select().fromTo(0, 1, 3, 4, 3, 3);
        scene.world().setBlocks(wall, Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().showSection(wall, Direction.DOWN);
        scene.idle(10);
        BlockPos viewer = util.grid().at(3, 1, 2);
        Vec3 picture = util.vector().of(2.5, 1.85, 2.9);
        scene.world().setBlock(viewer, CreateMediaSystems.IMAGE_VIEWER.get().defaultBlockState()
                .setValue(ImageViewerBlock.FACING, Direction.NORTH), false);
        scene.world().showSection(util.select().position(viewer), Direction.DOWN);
        scene.idle(15);
        say(scene, 90, util.vector().centerOf(viewer), "An Image Viewer goes on the side of a wall like a Display, and shows a picture from a link.");

        scene.overlay().showControls(util.vector().centerOf(viewer), Pointing.DOWN, 40).rightClick()
                .withItem(AllItems.WRENCH.asStack());
        say(scene, 120, util.vector().centerOf(viewer), "Right-click it with a Wrench to type the image's link: a PNG, JPEG, GIF (its first frame) or BMP, from a site the server allows. Each player's game downloads it.");

        ItemStack quill = new ItemStack(CreateMediaSystems.DISPLAY_QUILL.get());
        scene.overlay().showControls(util.vector().centerOf(viewer), Pointing.DOWN, 40).scroll().whileCTRL()
                .withItem(quill);
        scene.idle(10);
        scene.world().modifyBlockEntity(viewer, DisplayBlock.Entity.class, entity -> entity.resize(3 * DisplayBlock.UNIT,
                (int) Math.round(3 * DisplayBlock.UNIT / DisplayBlock.Ratio.WIDE.value), DisplayBlock.Ratio.WIDE));
        say(scene, 120, picture, "Size it with a Display Quill, just like a display. Shift+right-click's last ratio is the image's own.");

        scene.overlay().showControls(util.vector().centerOf(viewer), Pointing.DOWN, 40).rightClick().withItem(quill);
        scene.idle(10);
        scene.world().cycleBlockProperty(viewer, ImageViewerBlock.FRAMED);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, viewer,
                new AABB(1, 1, 2.9, 4, 2 + 11 / 16.0, 3), 120);
        say(scene, 120, picture, PonderPalette.GREEN, "Right-click it with the quill to take its frame away: only the picture is left, and the wall shows through where the image is transparent. Right-click again to put the frame back.");

        say(scene, 90, picture, "Through Engineer's Goggles it shows its link and size.");
    }

    /** A scene on a 5x5 plate, shown, titled {@code title}. */
    private static CreateSceneBuilder plate(SceneBuilder builder, String id, String title) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);
        return scene;
    }

    /** A text box at {@code at}, a key frame, shown {@code ticks} and waited out with a beat after. */
    private static void say(CreateSceneBuilder scene, int ticks, Vec3 at, String text) {
        scene.overlay().showText(ticks).attachKeyFrame().pointAt(at).placeNearTarget().text(text);
        scene.idle(ticks + 10);
    }

    private static void say(CreateSceneBuilder scene, int ticks, Vec3 at, PonderPalette color, String text) {
        scene.overlay().showText(ticks).attachKeyFrame().colored(color).pointAt(at).placeNearTarget().text(text);
        scene.idle(ticks + 10);
    }
}
