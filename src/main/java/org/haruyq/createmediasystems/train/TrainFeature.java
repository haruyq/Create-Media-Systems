package org.haruyq.createmediasystems.train;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.id;
import static org.haruyq.createmediasystems.CreateMediaSystems.legacyId;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

public final class TrainFeature implements Feature {
    public static final DeferredItem<AnnouncementScheduleItem> ANNOUNCEMENT_SCHEDULE = ITEMS.register(
            "announcement_schedule", () -> new AnnouncementScheduleItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<TrainDiscReaderBlock> TRAIN_DISC_READER = BLOCKS.register("train_disc_reader",
            () -> new TrainDiscReaderBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> TRAIN_DISC_READER_ITEM = ITEMS.registerSimpleBlockItem("train_disc_reader", TRAIN_DISC_READER);
    public static final Supplier<BlockEntityType<TrainDiscReaderBlock.Entity>> TRAIN_DISC_READER_ENTITY = BLOCK_ENTITIES.register(
            "train_disc_reader", () -> BlockEntityType.Builder.of(TrainDiscReaderBlock.Entity::new, TRAIN_DISC_READER.get()).build(null));
    public static final DeferredBlock<TrainSpeakerBlock> TRAIN_SPEAKER = BLOCKS.register("train_speaker",
            () -> new TrainSpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)));
    public static final DeferredItem<?> TRAIN_SPEAKER_ITEM = ITEMS.registerSimpleBlockItem("train_speaker", TRAIN_SPEAKER);
    public static final DeferredBlock<TrainDetectorBlock> TRAIN_DETECTOR = BLOCKS.register("train_detector",
            () -> new TrainDetectorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()));
    /** Placed by first right-clicking a track, like Create's Train Observer. */
    public static final DeferredItem<TrackTargetingBlockItem> TRAIN_DETECTOR_ITEM = ITEMS.register("train_detector",
            () -> new TrackTargetingBlockItem(TRAIN_DETECTOR.get(), new Item.Properties(), EdgePointType.OBSERVER));
    public static final Supplier<BlockEntityType<TrainDetectorBlock.Entity>> TRAIN_DETECTOR_ENTITY = BLOCK_ENTITIES.register(
            "train_detector", () -> BlockEntityType.Builder.of(TrainDetectorBlock.Entity::new, TRAIN_DETECTOR.get()).build(null));
    public static final DeferredBlock<TrainSignalReceiverBlock> TRAIN_SIGNAL_RECEIVER = BLOCKS.register("train_signal_receiver",
            () -> new TrainSignalReceiverBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_LAMP)
                    .lightLevel(state -> state.getValue(TrainSignalReceiverBlock.POWERED) ? 7 : 0)));
    public static final DeferredItem<?> TRAIN_SIGNAL_RECEIVER_ITEM =
            ITEMS.registerSimpleBlockItem("train_signal_receiver", TRAIN_SIGNAL_RECEIVER);
    public static final Supplier<BlockEntityType<TrainSignalReceiverBlock.Entity>> TRAIN_SIGNAL_RECEIVER_ENTITY =
            BLOCK_ENTITIES.register("train_signal_receiver", () -> BlockEntityType.Builder.of(
                    TrainSignalReceiverBlock.Entity::new, TRAIN_SIGNAL_RECEIVER.get()).build(null));

    @Override
    public void register(IEventBus modBus) {
        // The train disc reader was the train jukebox: worlds and trains that have one keep it.
        BLOCKS.addAlias(legacyId("train_jukebox"), id("train_disc_reader"));
        ITEMS.addAlias(legacyId("train_jukebox"), id("train_disc_reader"));
        BLOCK_ENTITIES.addAlias(legacyId("train_jukebox"), id("train_disc_reader"));
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            AnnouncementTypes.register();
            MovementBehaviour.REGISTRY.register(TRAIN_DISC_READER.get(), new TrainDiscReaderBehaviour());
            MovingInteractionBehaviour.REGISTRY.register(TRAIN_DISC_READER.get(), new TrainDiscReaderBehaviour.Interaction());
        }));
        NeoForge.EVENT_BUS.addListener(TrainSignals::onServerTick);
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        TrainDiscReaderBehaviour.registerPayloads(registrar);
        TrainSignals.registerPayloads(registrar);
    }
}
