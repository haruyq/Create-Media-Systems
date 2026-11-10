package org.haruyq.createmediasystems;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.audio.CustomDiscItem;
import org.haruyq.createmediasystems.audio.DiscReaderBlock;
import org.haruyq.createmediasystems.audio.RangeQuill;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.display.DisplayBlock;
import org.haruyq.createmediasystems.display.DisplayQuill;
import org.haruyq.createmediasystems.display.ImageViewerBlock;
import org.haruyq.createmediasystems.hifi.HifiCableItem;
import org.haruyq.createmediasystems.hifi.HifiSpeakerBlock;
import org.haruyq.createmediasystems.hifi.HifiStackBlock;
import org.haruyq.createmediasystems.hifi.HifiUnitItem;
import org.haruyq.createmediasystems.hifi.TapePosition;
import org.haruyq.createmediasystems.hifi.Unit;
import org.haruyq.createmediasystems.lighting.AnalysisCoordinator;
import org.haruyq.createmediasystems.lighting.AnalysisPackets;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingLinkItem;
import org.haruyq.createmediasystems.lighting.LightingMonitorBlock;
import org.haruyq.createmediasystems.lighting.LightingPackets;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.haruyq.createmediasystems.p2p.P2p;
import org.haruyq.createmediasystems.stream.JukeboxSettings;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.train.AnnouncementScheduleItem;
import org.haruyq.createmediasystems.train.AnnouncementTypes;
import org.haruyq.createmediasystems.train.TrainDetectorBlock;
import org.haruyq.createmediasystems.train.TrainDiscReaderBehaviour;
import org.haruyq.createmediasystems.train.TrainDiscReaderBlock;
import org.haruyq.createmediasystems.train.TrainSignalReceiverBlock;
import org.haruyq.createmediasystems.train.TrainSignals;
import org.haruyq.createmediasystems.train.TrainSpeakerBlock;
import org.haruyq.createmediasystems.video.EqualizerBlock;
import org.haruyq.createmediasystems.video.RemoteItem;
import org.haruyq.createmediasystems.video.VideoDiscItem;
import org.haruyq.createmediasystems.video.VideoPlayerBlock;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(CreateMediaSystems.MOD_ID)
public class CreateMediaSystems {
    public static final String MOD_ID = "create_media_systems";
    /** The mod's id before it was renamed: worlds from then name its blocks, items and data with it. */
    public static final String LEGACY_ID = "disksb";

    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);

    /** Placeholder event: every stream plays through it, the audio itself comes from {@code getStream}. */
    public static final DeferredHolder<SoundEvent, SoundEvent> STREAM =
            SOUNDS.register("stream", SoundEvent::createVariableRangeEvent);
    public static final DeferredHolder<SoundEvent, SoundEvent> NOTE_BLOCK_TRUMPET =
            SOUNDS.register("block.note_block.trumpet", SoundEvent::createVariableRangeEvent);
    public static final DeferredHolder<SoundEvent, SoundEvent> NOTE_BLOCK_TRUMPET_EXPOSED =
            SOUNDS.register("block.note_block.trumpet_exposed", SoundEvent::createVariableRangeEvent);
    public static final DeferredHolder<SoundEvent, SoundEvent> NOTE_BLOCK_TRUMPET_WEATHERED =
            SOUNDS.register("block.note_block.trumpet_weathered", SoundEvent::createVariableRangeEvent);
    public static final DeferredHolder<SoundEvent, SoundEvent> NOTE_BLOCK_TRUMPET_OXIDIZED =
            SOUNDS.register("block.note_block.trumpet_oxidized", SoundEvent::createVariableRangeEvent);
    public static final ResourceKey<JukeboxSong> CUSTOM_SONG = ResourceKey.create(Registries.JUKEBOX_SONG, id("custom"));

    public static final Supplier<DataComponentType<String>> URL = COMPONENTS.registerComponentType("url",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    /** A disc's video links, played in order. */
    public static final Supplier<DataComponentType<List<String>>> PLAYLIST = COMPONENTS.registerComponentType("playlist",
            b -> b.persistent(Codec.STRING.listOf())
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())));
    /** The Blu-ray player a remote is paired with. */
    public static final Supplier<DataComponentType<GlobalPos>> REMOTE_TARGET = COMPONENTS.registerComponentType(
            "remote_target", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));
    public static final Supplier<DataComponentType<UUID>> REMOTE_SOURCE = COMPONENTS.registerComponentType(
            "remote_source", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    /** The track's title from the API, shown on the disc. */
    public static final Supplier<DataComponentType<String>> TITLE = COMPONENTS.registerComponentType("title",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredItem<CustomDiscItem> CUSTOM_DISC = ITEMS.register("custom_disc",
            () -> new CustomDiscItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE).jukeboxPlayable(CUSTOM_SONG)));
    public static final DeferredItem<AnnouncementScheduleItem> ANNOUNCEMENT_SCHEDULE = ITEMS.register(
            "announcement_schedule", () -> new AnnouncementScheduleItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<TrainDiscReaderBlock> TRAIN_DISC_READER = BLOCKS.register("train_disc_reader",
            () -> new TrainDiscReaderBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> TRAIN_DISC_READER_ITEM = ITEMS.registerSimpleBlockItem("train_disc_reader", TRAIN_DISC_READER);
    public static final DeferredBlock<TrainSpeakerBlock> TRAIN_SPEAKER = BLOCKS.register("train_speaker",
            () -> new TrainSpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)));
    public static final DeferredItem<?> TRAIN_SPEAKER_ITEM = ITEMS.registerSimpleBlockItem("train_speaker", TRAIN_SPEAKER);
    /** Range quill: selects a vanilla jukebox's playback area and sets its volume (client-side UI only). */
    public static final DeferredItem<Item> RANGE_QUILL = ITEMS.register("range_quill",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final Supplier<AttachmentType<JukeboxSettings>> JUKEBOX_SETTINGS = ATTACHMENTS.register(
            "jukebox_settings", () -> AttachmentType.builder(() -> JukeboxSettings.DEFAULT)
                    .serialize(JukeboxSettings.SERIALIZER).build());
    public static final Supplier<AttachmentType<UUID>> SOURCE_ID = ATTACHMENTS.register(
            "source_id", () -> AttachmentType.builder(UUID::randomUUID).serialize(UUIDUtil.CODEC).build());
    public static final DeferredBlock<DiscReaderBlock> DISC_READER = BLOCKS.register("disc_reader",
            () -> new DiscReaderBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> DISC_READER_ITEM = ITEMS.registerSimpleBlockItem("disc_reader", DISC_READER);
    public static final Supplier<BlockEntityType<DiscReaderBlock.Entity>> DISC_READER_ENTITY = BLOCK_ENTITIES.register(
            "disc_reader", () -> BlockEntityType.Builder.of(DiscReaderBlock.Entity::new, DISC_READER.get()).build(null));
    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.register("speaker",
            () -> new SpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)));
    public static final DeferredItem<?> SPEAKER_ITEM = ITEMS.registerSimpleBlockItem("speaker", SPEAKER);
    public static final Supplier<BlockEntityType<SpeakerBlock.Entity>> SPEAKER_ENTITY = BLOCK_ENTITIES.register(
            "speaker", () -> BlockEntityType.Builder.of(SpeakerBlock.Entity::new, SPEAKER.get()).build(null));
    /** Every CMS item, in registration order, so new items show up without touching this. */
    public static final Supplier<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_media_systems"))
            .icon(() -> new ItemStack(CUSTOM_DISC.get()))
            .displayItems((params, output) -> ITEMS.getEntries().forEach(item -> output.accept(item.get())))
            .build());
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
    public static final Supplier<BlockEntityType<TrainDiscReaderBlock.Entity>> TRAIN_DISC_READER_ENTITY = BLOCK_ENTITIES.register(
            "train_disc_reader", () -> BlockEntityType.Builder.of(TrainDiscReaderBlock.Entity::new, TRAIN_DISC_READER.get()).build(null));
    /** A DVD holds fewer videos than a Blu-ray, and plays them at most 480 lines tall. */
    public static final DeferredItem<VideoDiscItem> DVD = ITEMS.register("dvd",
            () -> new VideoDiscItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON), 16, 480));
    public static final DeferredItem<VideoDiscItem> BLU_RAY = ITEMS.register("blu_ray",
            () -> new VideoDiscItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), 64, 1080));
    public static final DeferredBlock<VideoPlayerBlock> VIDEO_PLAYER = BLOCKS.register("blu_ray_player",
            () -> new VideoPlayerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> VIDEO_PLAYER_ITEM = ITEMS.registerSimpleBlockItem("blu_ray_player", VIDEO_PLAYER);
    public static final Supplier<BlockEntityType<VideoPlayerBlock.Entity>> VIDEO_PLAYER_ENTITY = BLOCK_ENTITIES.register(
            "blu_ray_player", () -> BlockEntityType.Builder.of(VideoPlayerBlock.Entity::new, VIDEO_PLAYER.get()).build(null));
    public static final DeferredItem<RemoteItem> REMOTE = ITEMS.register("remote",
            () -> new RemoteItem(new Item.Properties().stacksTo(1)));
    /** Display quill: links displays to a Blu-ray player and sets their size (client-side UI only). */
    public static final DeferredItem<Item> DISPLAY_QUILL = ITEMS.register("display_quill",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<DisplayBlock> DISPLAY = BLOCKS.register("display",
            () -> new DisplayBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).strength(1).noOcclusion()));
    public static final DeferredItem<?> DISPLAY_ITEM = ITEMS.registerSimpleBlockItem("display", DISPLAY);
    public static final DeferredBlock<ImageViewerBlock> IMAGE_VIEWER = BLOCKS.register("image_viewer",
            () -> new ImageViewerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).strength(1).noOcclusion()));
    public static final DeferredItem<?> IMAGE_VIEWER_ITEM = ITEMS.registerSimpleBlockItem("image_viewer", IMAGE_VIEWER);
    /** Displays' and image viewers' (an image viewer is a display that shows an image). */
    public static final Supplier<BlockEntityType<DisplayBlock.Entity>> DISPLAY_ENTITY = BLOCK_ENTITIES.register(
            "display", () -> BlockEntityType.Builder.of(DisplayBlock.Entity::new, DISPLAY.get(), IMAGE_VIEWER.get())
                    .build(null));
    public static final DeferredBlock<EqualizerBlock> EQUALIZER = BLOCKS.register("equalizer",
            () -> new EqualizerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> EQUALIZER_ITEM = ITEMS.registerSimpleBlockItem("equalizer", EQUALIZER);
    public static final Supplier<BlockEntityType<EqualizerBlock.Entity>> EQUALIZER_ENTITY = BLOCK_ENTITIES.register(
            "equalizer", () -> BlockEntityType.Builder.of(EqualizerBlock.Entity::new, EQUALIZER.get()).build(null));
    public static final DeferredBlock<StageLightBlock> STAGE_LIGHT = BLOCKS.register("stage_light",
            () -> new StageLightBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final DeferredItem<?> STAGE_LIGHT_ITEM = ITEMS.registerSimpleBlockItem("stage_light", STAGE_LIGHT);
    public static final DeferredBlock<StageLightBlock> LASER_PROJECTOR = BLOCKS.register("laser_projector",
            () -> new StageLightBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final DeferredItem<?> LASER_PROJECTOR_ITEM = ITEMS.registerSimpleBlockItem("laser_projector", LASER_PROJECTOR);
    public static final Supplier<BlockEntityType<StageLightBlock.Entity>> STAGE_LIGHT_ENTITY = BLOCK_ENTITIES.register(
            "stage_light", () -> BlockEntityType.Builder.of(StageLightBlock.Entity::new, STAGE_LIGHT.get(), LASER_PROJECTOR.get())
                    .build(null));
    public static final DeferredBlock<LightingConsoleBlock> LIGHTING_CONSOLE = BLOCKS.register("lighting_console",
            () -> new LightingConsoleBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final DeferredItem<?> LIGHTING_CONSOLE_ITEM = ITEMS.registerSimpleBlockItem("lighting_console", LIGHTING_CONSOLE);
    public static final DeferredBlock<LightingMonitorBlock> LIGHTING_MONITOR = BLOCKS.register("lighting_monitor",
            () -> new LightingMonitorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final DeferredItem<?> LIGHTING_MONITOR_ITEM = ITEMS.registerSimpleBlockItem("lighting_monitor", LIGHTING_MONITOR);
    public static final Supplier<BlockEntityType<LightingConsoleBlock.Entity>> LIGHTING_CONSOLE_ENTITY = BLOCK_ENTITIES.register(
            "lighting_console", () -> BlockEntityType.Builder.of(LightingConsoleBlock.Entity::new, LIGHTING_CONSOLE.get()).build(null));
    public static final Supplier<DataComponentType<GlobalPos>> LIGHTING_TARGET = COMPONENTS.registerComponentType(
            "lighting_target", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));
    public static final Supplier<DataComponentType<UUID>> LIGHTING_SOURCE = COMPONENTS.registerComponentType(
            "lighting_source", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    public static final DeferredItem<LightingLinkItem> LIGHTING_LINK = ITEMS.register("lighting_link",
            () -> new LightingLinkItem(new Item.Properties().stacksTo(1)));
    /** A hi-fi system's units, stacked in one block; each unit has its own item (see HifiUnitItem), the block none. */
    public static final DeferredBlock<HifiStackBlock> HIFI_STACK = BLOCKS.register("hifi_stack",
            () -> new HifiStackBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(1.5f)
                    .noOcclusion().dynamicShape().noLootTable()));
    public static final Supplier<BlockEntityType<HifiStackBlock.Entity>> HIFI_STACK_ENTITY = BLOCK_ENTITIES.register(
            "hifi_stack", () -> BlockEntityType.Builder.of(HifiStackBlock.Entity::new, HIFI_STACK.get()).build(null));
    private static final Map<Unit, DeferredItem<HifiUnitItem>> HIFI_UNITS = new EnumMap<>(Unit.class);

    static {
        for (Unit unit : Unit.values()) {
            HIFI_UNITS.put(unit, ITEMS.register("hifi_" + unit.id,
                    () -> new HifiUnitItem(HIFI_STACK.get(), unit, new Item.Properties())));
        }
    }

    public static final DeferredBlock<HifiSpeakerBlock> HIFI_SPEAKER = BLOCKS.register("hifi_speaker",
            () -> new HifiSpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK).noOcclusion()));
    public static final DeferredItem<?> HIFI_SPEAKER_ITEM = ITEMS.registerSimpleBlockItem("hifi_speaker", HIFI_SPEAKER);
    public static final Supplier<BlockEntityType<HifiSpeakerBlock.Entity>> HIFI_SPEAKER_ENTITY = BLOCK_ENTITIES.register(
            "hifi_speaker", () -> BlockEntityType.Builder.of(HifiSpeakerBlock.Entity::new, HIFI_SPEAKER.get()).build(null));
    public static final DeferredItem<HifiCableItem> HIFI_CABLE = ITEMS.register("audio_cable",
            () -> new HifiCableItem(new Item.Properties()));
    /** A CD and a cassette hold links to sound alone, for a hi-fi stack's disc player and its deck. */
    public static final DeferredItem<VideoDiscItem> CD = ITEMS.register("cd",
            () -> new VideoDiscItem(new Item.Properties().stacksTo(1), 24, 0));
    public static final DeferredItem<VideoDiscItem> CASSETTE = ITEMS.register("cassette",
            () -> new VideoDiscItem(new Item.Properties().stacksTo(1), 12, 0));
    /** Where a cassette was stopped. */
    public static final Supplier<DataComponentType<TapePosition>> TAPE_POSITION = COMPONENTS.registerComponentType(
            "tape_position", b -> b.persistent(TapePosition.CODEC).networkSynchronized(TapePosition.STREAM_CODEC));

    public CreateMediaSystems(IEventBus modBus, ModContainer container) {
        ServerConfig.register(modBus, container);
        SOUNDS.register(modBus);
        COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        // Worlds from before the rename keep what they have of the mod.
        for (DeferredRegister<?> register : List.of(SOUNDS, COMPONENTS, BLOCKS, ITEMS, BLOCK_ENTITIES, ATTACHMENTS, TABS)) {
            for (DeferredHolder<?, ?> entry : register.getEntries()) {
                register.addAlias(legacyId(entry.getId().getPath()), entry.getId());
            }
        }
        // The train disc reader was the train jukebox: worlds and trains that have one keep it.
        BLOCKS.addAlias(legacyId("train_jukebox"), id("train_disc_reader"));
        ITEMS.addAlias(legacyId("train_jukebox"), id("train_disc_reader"));
        BLOCK_ENTITIES.addAlias(legacyId("train_jukebox"), id("train_disc_reader"));
        NeoForge.EVENT_BUS.addListener(CreateMediaSystems::renameLegacySavedData);
        ATTACHMENTS.register(modBus);
        TABS.register(modBus);
        modBus.addListener(CreateMediaSystems::setup);
        modBus.addListener(CreateMediaSystems::registerPayloads);
        NeoForge.EVENT_BUS.addListener(TrainSignals::onServerTick);
        AnalysisCoordinator.register();
        P2p.register();
    }

    public static HifiUnitItem hifiUnit(Unit unit) {
        return HIFI_UNITS.get(unit).get();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceLocation legacyId(String path) {
        return ResourceLocation.fromNamespaceAndPath(LEGACY_ID, path);
    }

    /** A world from before the rename keeps the mod's saved data as data/LEGACY_ID_*.dat; it becomes MOD_ID_*.dat. */
    private static void renameLegacySavedData(ServerAboutToStartEvent event) {
        String prefix = LEGACY_ID + "_";
        // A dimension's data is at most <world>/dimensions/<namespace>/<path>/data/<name>.dat.
        try (Stream<Path> files = Files.walk(event.getServer().getWorldPath(LevelResource.ROOT), 5)) {
            for (Path file : files.filter(f -> f.getParent() != null && f.getParent().endsWith("data")
                    && f.getFileName().toString().startsWith(prefix)).toList()) {
                Path renamed = file.resolveSibling(MOD_ID + "_" + file.getFileName().toString().substring(prefix.length()));
                if (!Files.exists(renamed)) {
                    Files.move(file, renamed);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            LogUtils.getLogger().warn("Could not rename this world's saved data from before the mod was renamed", e);
        }
    }

    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            AnnouncementTypes.register();
            MovementBehaviour.REGISTRY.register(TRAIN_DISC_READER.get(), new TrainDiscReaderBehaviour());
            MovingInteractionBehaviour.REGISTRY.register(TRAIN_DISC_READER.get(), new TrainDiscReaderBehaviour.Interaction());
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("13");
        ServerConfig.registerPayloads(registrar);
        StreamPackets.registerPayloads(registrar);
        TrainDiscReaderBehaviour.registerPayloads(registrar);
        TrainSignals.registerPayloads(registrar);
        RangeQuill.registerPayloads(registrar);
        CustomDiscItem.registerPayloads(registrar);
        VideoDiscItem.registerPayloads(registrar);
        RemoteItem.registerPayloads(registrar);
        EqualizerBlock.registerPayloads(registrar);
        HifiStackBlock.registerPayloads(registrar);
        DisplayQuill.registerPayloads(registrar);
        ImageViewerBlock.registerPayloads(registrar);
        LightingPackets.register(registrar);
        AnalysisPackets.register(registrar);
        P2p.registerPayloads(registrar);
    }

}
