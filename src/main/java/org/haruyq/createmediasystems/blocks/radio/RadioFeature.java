package org.haruyq.createmediasystems.blocks.radio;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.Locale;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;
import org.haruyq.createmediasystems.ServerConfig;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Radio: a transmitter sends its source's track from the antenna on top of a mast, on a frequency; whoever carries a
 * receiver tuned to it within the tower's reach hears it (see RadioStations).
 */
public final class RadioFeature implements Feature {
    /** The FM band, in tenths of a MHz: 76.0 to 108.0. */
    public static final int MIN_FREQUENCY = 760, MAX_FREQUENCY = 1080, DEFAULT_FREQUENCY = 800;
    /** How far (blocks) an antenna with no mast under it reaches. */
    public static final int BASE_REACH = 64;
    public static final ModConfigSpec.IntValue REACH_PER_MAST = ServerConfig.intSetting("radioReachPerMast",
            "radio_reach_per_mast", "Blocks of reach each mast block under a radio antenna adds to its 64.", 32, 1, 256);

    public static final DeferredBlock<RadioMastBlock> MAST = BLOCKS.register("radio_mast",
            () -> new RadioMastBlock(metal(), Block.box(6, 0, 6, 10, 16, 10)));
    public static final DeferredBlock<RadioMastBlock> ANTENNA = BLOCKS.register("radio_antenna",
            () -> new RadioMastBlock(metal(), Block.box(5, 0, 5, 11, 16, 11)));
    public static final DeferredBlock<RadioTransmitterBlock> TRANSMITTER = BLOCKS.register("radio_transmitter",
            () -> new RadioTransmitterBlock(metal()));

    public static final DeferredItem<BlockItem> MAST_ITEM = ITEMS.registerSimpleBlockItem(MAST);
    public static final DeferredItem<BlockItem> ANTENNA_ITEM = ITEMS.registerSimpleBlockItem(ANTENNA);
    public static final DeferredItem<BlockItem> TRANSMITTER_ITEM = ITEMS.registerSimpleBlockItem(TRANSMITTER);

    public static final Supplier<BlockEntityType<RadioTransmitterBlock.Transmitter>> TRANSMITTER_ENTITY =
            BLOCK_ENTITIES.register("radio_transmitter", () -> BlockEntityType.Builder
                    .of(RadioTransmitterBlock.Transmitter::new, TRANSMITTER.get()).build(null));
    /** The transmitter a wrench has picked, which it links towers and sources to (see RadioLink). */
    /** The hiss of a receiver that gets no station, or a weak one. */
    public static final Supplier<SoundEvent> STATIC = CreateMediaSystems.SOUNDS.register("radio_static",
            SoundEvent::createVariableRangeEvent);
    public static final Supplier<DataComponentType<GlobalPos>> RADIO_TARGET = COMPONENTS.registerComponentType(
            "radio_target", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().strength(2.5f, 6).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops();
    }

    public static int clampFrequency(int frequency) {
        return Mth.clamp(frequency, MIN_FREQUENCY, MAX_FREQUENCY);
    }

    /** {@code frequency} (tenths of a MHz) as it is shown: 80.0. */
    public static String mhz(int frequency) {
        return String.format(Locale.ROOT, "%d.%d", frequency / 10, frequency % 10);
    }

    /** How far (blocks) an antenna on {@code masts} mast blocks reaches. */
    public static int reach(int masts) {
        return BASE_REACH + REACH_PER_MAST.getAsInt() * masts;
    }

    @Override
    public void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(RadioStations::tick);
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        RadioPackets.register(registrar);
    }
}
