package org.haruyq.createmediasystems.blocks.train;

import static org.haruyq.createmediasystems.client.PonderScenes.notes;
import static org.haruyq.createmediasystems.client.PonderScenes.say;

import org.haruyq.createmediasystems.client.ClientFeature;

import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.station.StationBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.neoforged.bus.api.IEventBus;

public final class TrainClient implements ClientFeature {
    @Override
    public void register(IEventBus modBus) {
        TrainDetectorRenderer.register(modBus);
    }

    @Override
    public void registerPonder(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(TrainFeature.TRAIN_DISC_READER.getId(), TrainFeature.TRAIN_SPEAKER.getId(), TrainFeature.ANNOUNCEMENT_SCHEDULE.getId())
                .addStoryBoard(Create.asResource("train_controls"), TrainClient::trainDiscReader);
        helper.forComponents(TrainFeature.TRAIN_DETECTOR.getId(), TrainFeature.TRAIN_SIGNAL_RECEIVER.getId())
                .addStoryBoard(Create.asResource("train_observer"), TrainClient::trainDetector);
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

        ItemStack schedule = new ItemStack(TrainFeature.ANNOUNCEMENT_SCHEDULE.get());
        say(scene, 100, util.vector().centerOf(jukeboxPos), "An Announcement Schedule is edited like a Train Schedule: add a station, then announcements for the trip there, each with its own link: on departure, after N seconds, at N%% of the way, N seconds before arrival, or on arrival.");

        scene.world().setBlock(jukeboxPos, TrainFeature.TRAIN_DISC_READER.get().defaultBlockState(), false);
        scene.world().showSectionAndMerge(jukebox, Direction.DOWN, trainElement);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(jukeboxPos), Pointing.DOWN, 50).rightClick().withItem(schedule);
        say(scene, 90, util.vector().centerOf(jukeboxPos), "Put it in a Train Disc Reader on the carriage. It can be swapped even after the train is assembled.");

        scene.world().setBlock(speakerPos,
                TrainFeature.TRAIN_SPEAKER.get().defaultBlockState().setValue(TrainSpeakerBlock.FACING, Direction.WEST), false);
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
        scene.world().setBlock(detector, TrainFeature.TRAIN_DETECTOR.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(detector), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(util.vector().topOf(util.grid().at(5, 1, 7)), Pointing.DOWN, 40).rightClick()
                .withItem(new ItemStack(TrainFeature.TRAIN_DETECTOR_ITEM.get()));
        say(scene, 80, util.vector().centerOf(detector), "Place a Train Detector like a Train Observer: right-click the track first, then place it nearby.");

        scene.world().setBlock(receiver, TrainFeature.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState(), false);
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
        scene.world().setBlock(receiver, TrainFeature.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState()
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
        scene.world().setBlock(receiver, TrainFeature.TRAIN_SIGNAL_RECEIVER.get().defaultBlockState(), false);
        scene.world().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), false);
        scene.idle(70);
    }
}
