package org.haruyq.createmediasystems.broadcast;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.List;
import java.util.function.Supplier;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
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

    public static final DeferredItem<BlockItem> BASE_ITEM = item(BASE, "broadcast.base.power", BroadcastBaseBlock.Base.MIN_RPM);
    public static final DeferredItem<BlockItem> MAST_ITEM = item(MAST, "broadcast.mast.place");
    public static final DeferredItem<BlockItem> HORN_ITEM = item(HORN, "broadcast.horn.place", BroadcastProfile.HORN.reach);
    public static final DeferredItem<BlockItem> ARRAY_ITEM = item(ARRAY, "broadcast.array.place", BroadcastProfile.ARRAY.reach);
    public static final DeferredItem<BlockItem> DIGITAL_HORN_ITEM = item(DIGITAL_HORN, "broadcast.horn.place",
            BroadcastProfile.DIGITAL_HORN.reach);

    public static final Supplier<BlockEntityType<BroadcastBaseBlock.Base>> BASE_ENTITY = BLOCK_ENTITIES.register("broadcast_base",
            () -> BlockEntityType.Builder.of(BroadcastBaseBlock.Base::new, BASE.get()).build(null));
    public static final Supplier<BlockEntityType<BroadcastTowerBlock.Tower>> TOWER_ENTITY = BLOCK_ENTITIES.register("broadcast_tower",
            () -> BlockEntityType.Builder.of(BroadcastTowerBlock.Tower::new, HORN.get(), DIGITAL_HORN.get(), ARRAY.get()).build(null));

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().strength(2.5f, 6).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops();
    }

    private static DeferredItem<BlockItem> item(DeferredBlock<? extends Block> block, String hint, Object... values) {
        return ITEMS.register(block.getId().getPath(), () -> new BlockItem(block.get(), new Item.Properties()) {
            @Override
            public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
                tooltip.add(Component.translatable(hint, values).withStyle(ChatFormatting.GRAY));
                if (getBlock() instanceof BroadcastTowerBlock) {
                    tooltip.add(Component.translatable("broadcast.tower.link").withStyle(ChatFormatting.AQUA));
                } else if (getBlock() instanceof BroadcastBaseBlock) {
                    tooltip.add(Component.translatable("broadcast.base.reserve").withStyle(ChatFormatting.GRAY));
                }
            }
        });
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
