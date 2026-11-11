package org.haruyq.createmediasystems.hifi;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.stream.DiscKind;
import org.haruyq.createmediasystems.video.VideoDiscItem;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

public final class HifiFeature implements Feature {
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
    public static final DeferredItem<VideoDiscItem> CD = ITEMS.register("cd",
            () -> new VideoDiscItem(new Item.Properties().stacksTo(1), DiscKind.CD));
    public static final DeferredItem<VideoDiscItem> CASSETTE = ITEMS.register("cassette",
            () -> new VideoDiscItem(new Item.Properties().stacksTo(1), DiscKind.CASSETTE));
    public static final Supplier<DataComponentType<TapePosition>> TAPE_POSITION = COMPONENTS.registerComponentType(
            "tape_position", b -> b.persistent(TapePosition.CODEC).networkSynchronized(TapePosition.STREAM_CODEC));

    public static HifiUnitItem hifiUnit(Unit unit) {
        return HIFI_UNITS.get(unit).get();
    }

    /** Every unit's item, in the order of Unit. */
    public static Collection<DeferredItem<HifiUnitItem>> hifiUnits() {
        return HIFI_UNITS.values();
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        HifiStackBlock.registerPayloads(registrar);
    }
}
