package org.haruyq.createmediasystems.piano;

import java.util.function.Supplier;
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

public final class PianoFeature implements Feature {
    public static final DeferredBlock<GrandPianoBlock> PIANO = CreateMediaSystems.BLOCKS.register("grand_piano",
            () -> new GrandPianoBlock(BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.WOOD)
                    .noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> PIANO_ITEM = CreateMediaSystems.ITEMS.register("grand_piano",
            () -> new BlockItem(PIANO.get(), new Item.Properties().stacksTo(1)) {
                @Override
                protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
                    var level = context.getLevel();
                    return level.isClientSide ? super.placeBlock(context, state)
                            : GrandPianoBlock.placeParts(level, context.getClickedPos(), state);
                }
            });
    public static final Supplier<BlockEntityType<PianoBlockEntity>> PIANO_ENTITY = CreateMediaSystems.BLOCK_ENTITIES.register(
            "grand_piano", () -> BlockEntityType.Builder.of(PianoBlockEntity::new, PIANO.get()).build(null));

    @Override public void registerPayloads(PayloadRegistrar registrar) { PianoPackets.register(registrar); }
}
