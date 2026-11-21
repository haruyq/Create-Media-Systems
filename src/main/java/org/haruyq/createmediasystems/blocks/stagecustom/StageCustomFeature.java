package org.haruyq.createmediasystems.blocks.stagecustom;

import java.util.EnumMap;
import java.util.Map;
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
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;

public final class StageCustomFeature implements Feature {
    public static final String ID = "stage_custom_birch_sbp2f5_dus";
    public static final DeferredBlock<StageCustomBlock> BLOCK = CreateMediaSystems.BLOCKS.register(ID,
            () -> new StageCustomBlock(BlockBehaviour.Properties.of().strength(2).sound(SoundType.WOOD)
                    .noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> ITEM = CreateMediaSystems.ITEMS.register(ID,
            () -> new BlockItem(BLOCK.get(), new Item.Properties().stacksTo(1)) {
                @Override
                protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
                    return context.getLevel().isClientSide ? super.placeBlock(context, state)
                            : StageCustomBlock.install(context.getLevel(), context.getClickedPos(), state, context.getPlayer());
                }
            });
    public static final Supplier<BlockEntityType<StageCustomBlock.Entity>> ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            ID, () -> BlockEntityType.Builder.of(StageCustomBlock.Entity::new, BLOCK.get()).build(null));
    private static final Map<Drum, Supplier<SoundEvent>> SOUNDS = sounds();

    private static Map<Drum, Supplier<SoundEvent>> sounds() {
        var result = new EnumMap<Drum, Supplier<SoundEvent>>(Drum.class);
        for (Drum drum : Drum.values()) {
            result.put(drum, CreateMediaSystems.SOUNDS.register("stage_custom." + drum.id(), SoundEvent::createVariableRangeEvent));
        }
        return Map.copyOf(result);
    }

    public static SoundEvent sound(Drum drum) {
        return SOUNDS.get(drum).get();
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        StageCustomMidi.register(registrar);
    }
}
