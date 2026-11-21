package org.haruyq.createmediasystems.blocks.stagecustom;

import java.util.function.Supplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Feature;

public final class StageCustomFeature implements Feature {
    public static final String ID = "stage_custom_birch_sbp2f5_dus";
    public static final String COMPLETE_ID = "stage_custom_birch_sbp2f5azm_dus";
    public static final DeferredBlock<StageCustomBlock> BLOCK = registerBlock(ID, false);
    public static final DeferredBlock<StageCustomBlock> COMPLETE_BLOCK = registerBlock(COMPLETE_ID, true);
    public static final DeferredItem<BlockItem> ITEM = registerItem(ID, BLOCK);
    public static final DeferredItem<BlockItem> COMPLETE_ITEM = registerItem(COMPLETE_ID, COMPLETE_BLOCK);
    public static final Supplier<BlockEntityType<StageCustomBlock.Entity>> ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            ID, () -> BlockEntityType.Builder.of(StageCustomBlock.Entity::new, BLOCK.get(), COMPLETE_BLOCK.get()).build(null));
    public static final Supplier<SoundEvent> PLAYED = CreateMediaSystems.SOUNDS.register("stage_custom", SoundEvent::createVariableRangeEvent);

    private static DeferredBlock<StageCustomBlock> registerBlock(String id, boolean complete) {
        return CreateMediaSystems.BLOCKS.register(id, () -> new StageCustomBlock(BlockBehaviour.Properties.of().strength(2)
                .sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK), complete));
    }

    private static DeferredItem<BlockItem> registerItem(String id, DeferredBlock<StageCustomBlock> block) {
        return CreateMediaSystems.ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties().stacksTo(1)) {
            @Override
            protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
                return context.getLevel().isClientSide ? super.placeBlock(context, state)
                        : StageCustomBlock.install(context.getLevel(), context.getClickedPos(), state, context.getPlayer());
            }
        });
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        StageCustomMidi.register(registrar);
    }
}
