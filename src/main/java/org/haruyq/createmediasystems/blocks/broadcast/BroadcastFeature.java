package org.haruyq.createmediasystems.blocks.broadcast;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.List;
import java.util.function.Supplier;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.Feature;

public final class BroadcastFeature implements Feature {
    public static final DeferredBlock<BroadcastBaseBlock> BASE = BLOCKS.register("broadcast_base",
            () -> new BroadcastBaseBlock(metal()));
    public static final DeferredBlock<BroadcastMastBlock> MAST = BLOCKS.register("broadcast_mast",
            () -> new BroadcastMastBlock(metal()));
    public static final DeferredBlock<BroadcastTowerBlock> HORN = BLOCKS.register("broadcast_horn",
            () -> new BroadcastTowerBlock(metal(), BroadcastProfile.HORN));
    public static final DeferredBlock<BroadcastTowerBlock> ARRAY = BLOCKS.register("broadcast_array",
            () -> new BroadcastTowerBlock(metal(), BroadcastProfile.ARRAY));
    public static final DeferredBlock<BroadcastTowerBlock> DIGITAL_HORN = BLOCKS.register("broadcast_digital_horn",
            () -> new BroadcastTowerBlock(metal(), BroadcastProfile.DIGITAL_HORN));

    public static final DeferredItem<BlockItem> BASE_ITEM = ITEMS.registerSimpleBlockItem(BASE);
    public static final DeferredItem<BlockItem> MAST_ITEM = ITEMS.registerSimpleBlockItem(MAST);
    public static final DeferredItem<BlockItem> HORN_ITEM = ITEMS.registerSimpleBlockItem(HORN);
    public static final DeferredItem<BlockItem> ARRAY_ITEM = ITEMS.registerSimpleBlockItem(ARRAY);
    public static final DeferredItem<BlockItem> DIGITAL_HORN_ITEM = ITEMS.registerSimpleBlockItem(DIGITAL_HORN);

    public static final Supplier<BlockEntityType<BroadcastBaseBlock.Base>> BASE_ENTITY = BLOCK_ENTITIES.register("broadcast_base",
            () -> BlockEntityType.Builder.of(BroadcastBaseBlock.Base::new, BASE.get()).build(null));
    public static final Supplier<BlockEntityType<BroadcastTowerBlock.Tower>> TOWER_ENTITY = BLOCK_ENTITIES.register("broadcast_tower",
            () -> BlockEntityType.Builder.of(BroadcastTowerBlock.Tower::new, HORN.get(), DIGITAL_HORN.get(), ARRAY.get()).build(null));

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().strength(2.5f, 6).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops();
    }


    @Override
    public void register(IEventBus modBus) {
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            var tower = new MovingBroadcastTower();
            for (var head : List.of(HORN, DIGITAL_HORN, ARRAY)) MovementBehaviour.REGISTRY.register(head.get(), tower);
            MovingInteractionBehaviour.REGISTRY.register(BASE.get(), new MovingBroadcastTower.BaseInteraction());
        }));
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                BASE_ENTITY.get(), (base, face) -> base.port()));
        NeoForge.EVENT_BUS.addListener(BroadcastCoverage::tick);
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        MovingBroadcastPackets.register(registrar);
    }
}
