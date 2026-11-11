package org.haruyq.createmediasystems.video;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.SOUNDS;
import static org.haruyq.createmediasystems.CreateMediaSystems.id;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.ServerConfig;

import com.mojang.serialization.Codec;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** DVDs and Blu-rays, the Blu-ray player with its remote and equalizers, and the disc drive emulator. */
public final class VideoFeature implements Feature {
    public static final ModConfigSpec.IntValue REMOTE_RANGE = ServerConfig.intSetting("remoteRange", "blocks",
            "Farthest (in blocks) a remote reaches the Blu-ray player it is paired with.", 64, 1, 4096);

    /** A disc's video links, played in order. */
    public static final Supplier<DataComponentType<List<String>>> PLAYLIST = COMPONENTS.registerComponentType("playlist",
            b -> b.persistent(Codec.STRING.listOf())
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())));
    /** The Blu-ray player a remote is paired with. */
    public static final Supplier<DataComponentType<GlobalPos>> REMOTE_TARGET = COMPONENTS.registerComponentType(
            "remote_target", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));
    public static final Supplier<DataComponentType<UUID>> REMOTE_SOURCE = COMPONENTS.registerComponentType(
            "remote_source", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** A disc drive emulator's: its disc turning (a loop), its tray closing and opening, and its head finding the disc's start. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_SPIN =
            SOUNDS.register("drive_spin", () -> SoundEvent.createVariableRangeEvent(id("drive_spin")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_TRAY =
            SOUNDS.register("drive_tray", () -> SoundEvent.createVariableRangeEvent(id("drive_tray")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_TRAY_OPEN =
            SOUNDS.register("drive_tray_open", () -> SoundEvent.createVariableRangeEvent(id("drive_tray_open")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_SEEK =
            SOUNDS.register("drive_seek", () -> SoundEvent.createVariableRangeEvent(id("drive_seek")));

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
    /** The disc drive of its player's real computer, beside the Blu-ray player or disc reader that plays its disc. */
    public static final DeferredBlock<DriveEmulatorBlock> DRIVE_EMULATOR = BLOCKS.register("disc_drive_emulator",
            () -> new DriveEmulatorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> DRIVE_EMULATOR_ITEM = ITEMS.registerSimpleBlockItem("disc_drive_emulator", DRIVE_EMULATOR);
    public static final Supplier<BlockEntityType<DriveEmulatorBlock.Entity>> DRIVE_EMULATOR_ENTITY = BLOCK_ENTITIES.register(
            "disc_drive_emulator", () -> BlockEntityType.Builder.of(DriveEmulatorBlock.Entity::new, DRIVE_EMULATOR.get()).build(null));
    public static final DeferredItem<RemoteItem> REMOTE = ITEMS.register("remote",
            () -> new RemoteItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<EqualizerBlock> EQUALIZER = BLOCKS.register("equalizer",
            () -> new EqualizerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> EQUALIZER_ITEM = ITEMS.registerSimpleBlockItem("equalizer", EQUALIZER);
    public static final Supplier<BlockEntityType<EqualizerBlock.Entity>> EQUALIZER_ENTITY = BLOCK_ENTITIES.register(
            "equalizer", () -> BlockEntityType.Builder.of(EqualizerBlock.Entity::new, EQUALIZER.get()).build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        VideoDiscItem.registerPayloads(registrar);
        RemoteItem.registerPayloads(registrar);
        DriveEmulatorBlock.registerPayloads(registrar);
        EqualizerBlock.registerPayloads(registrar);
    }
}
