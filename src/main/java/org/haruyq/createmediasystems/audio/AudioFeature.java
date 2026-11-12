package org.haruyq.createmediasystems.audio;

import static org.haruyq.createmediasystems.CreateMediaSystems.ATTACHMENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;
import static org.haruyq.createmediasystems.CreateMediaSystems.SOUNDS;
import static org.haruyq.createmediasystems.CreateMediaSystems.id;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.PlaybackClock;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class AudioFeature implements Feature {
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
    /** The track's title from the API, shown on the disc. */
    public static final Supplier<DataComponentType<String>> TITLE = COMPONENTS.registerComponentType("title",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredItem<CustomDiscItem> CUSTOM_DISC = ITEMS.register("custom_disc",
            () -> new CustomDiscItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE).jukeboxPlayable(CUSTOM_SONG)));
    public static final DeferredItem<Item> RANGE_QUILL = ITEMS.register("range_quill",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final Supplier<AttachmentType<EmitterRange>> JUKEBOX_SETTINGS = ATTACHMENTS.register(
            "jukebox_settings", () -> AttachmentType.builder(() -> EmitterRange.DEFAULT)
                    .serialize(RangeQuill.JUKEBOX_SERIALIZER).build());
    /**
     * Where a jukebox's custom disc is in its track. Not saved: its song player keeps that, and the clock takes it
     * up from there.
     */
    public static final Supplier<AttachmentType<PlaybackClock>> JUKEBOX_CLOCK = ATTACHMENTS.register(
            "jukebox_clock", () -> AttachmentType.builder(() -> new PlaybackClock()).build());
    /** Who locked the tuning of which note block in a chunk. */
    public static final Supplier<AttachmentType<Map<BlockPos, UUID>>> NOTE_BLOCK_LOCKS = ATTACHMENTS.register(
            "note_block_locks", () -> AttachmentType.<Map<BlockPos, UUID>>builder(() -> Map.of())
                    .serialize(NoteBlockLocks.CODEC).build());
    public static final DeferredBlock<DiscReaderBlock> DISC_READER =BLOCKS.register("disc_reader",
            () -> new DiscReaderBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX)));
    public static final DeferredItem<?> DISC_READER_ITEM = ITEMS.registerSimpleBlockItem("disc_reader", DISC_READER);
    public static final Supplier<BlockEntityType<DiscReaderBlock.Entity>> DISC_READER_ENTITY = BLOCK_ENTITIES.register(
            "disc_reader", () -> BlockEntityType.Builder.of(DiscReaderBlock.Entity::new, DISC_READER.get()).build(null));
    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.register("speaker",
            () -> new SpeakerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)));
    public static final DeferredItem<?> SPEAKER_ITEM = ITEMS.registerSimpleBlockItem("speaker", SPEAKER);
    public static final Supplier<BlockEntityType<SpeakerBlock.Entity>> SPEAKER_ENTITY = BLOCK_ENTITIES.register(
            "speaker", () -> BlockEntityType.Builder.of(SpeakerBlock.Entity::new, SPEAKER.get()).build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        RangeQuill.registerPayloads(registrar);
        CustomDiscItem.registerPayloads(registrar);
    }
}
