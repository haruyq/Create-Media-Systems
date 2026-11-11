package org.haruyq.createmediasystems.lighting;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.COMPONENTS;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.UUID;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/** Stage lights and laser projectors, the lighting console that drives them, and the LED tuning wrench that links them. */
public final class LightingFeature implements Feature {
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

    @Override
    public void register(IEventBus modBus) {
        AnalysisCoordinator.register();
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        LightingPackets.register(registrar);
        AnalysisPackets.register(registrar);
    }
}
