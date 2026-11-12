package org.haruyq.createmediasystems.disc;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.SOUNDS;
import static org.haruyq.createmediasystems.CreateMediaSystems.id;

import java.util.List;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.stream.DiscKind;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** The discs and tapes that hold a playlist, whatever plays them, and the drive emulator that shares a real disc. */
public final class DiscFeature implements Feature {
    public static final Supplier<DataComponentType<List<String>>> PLAYLIST = COMPONENTS.registerComponentType("playlist",
            b -> b.persistent(Codec.STRING.listOf())
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())));

    public static final DeferredItem<PlaylistItem> DVD = ITEMS.register("dvd",
            () -> new PlaylistItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON), DiscKind.DVD));
    public static final DeferredItem<PlaylistItem> BLU_RAY = ITEMS.register("blu_ray",
            () -> new PlaylistItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), DiscKind.BLU_RAY));
    public static final DeferredItem<PlaylistItem> CD = ITEMS.register("cd",
            () -> new PlaylistItem(new Item.Properties().stacksTo(1), DiscKind.CD));
    public static final DeferredItem<PlaylistItem> CASSETTE = ITEMS.register("cassette",
            () -> new PlaylistItem(new Item.Properties().stacksTo(1), DiscKind.CASSETTE));

    /** A disc drive emulator's: its disc turning (a loop), its tray closing and opening, and its head finding the disc's start. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_SPIN =
            SOUNDS.register("drive_spin", () -> SoundEvent.createVariableRangeEvent(id("drive_spin")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_TRAY =
            SOUNDS.register("drive_tray", () -> SoundEvent.createVariableRangeEvent(id("drive_tray")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_TRAY_OPEN =
            SOUNDS.register("drive_tray_open", () -> SoundEvent.createVariableRangeEvent(id("drive_tray_open")));
    public static final DeferredHolder<SoundEvent, SoundEvent> DRIVE_SEEK =
            SOUNDS.register("drive_seek", () -> SoundEvent.createVariableRangeEvent(id("drive_seek")));

    public static final DeferredBlock<DriveEmulatorBlock> DRIVE_EMULATOR = BLOCKS.register("disc_drive_emulator",
            () -> new DriveEmulatorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> DRIVE_EMULATOR_ITEM = ITEMS.registerSimpleBlockItem("disc_drive_emulator", DRIVE_EMULATOR);
    public static final Supplier<BlockEntityType<DriveEmulatorBlock.Entity>> DRIVE_EMULATOR_ENTITY = BLOCK_ENTITIES.register(
            "disc_drive_emulator", () -> BlockEntityType.Builder.of(DriveEmulatorBlock.Entity::new, DRIVE_EMULATOR.get()).build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        PlaylistItem.registerPayloads(registrar);
        DriveEmulatorBlock.registerPayloads(registrar);
    }
}
