package org.haruyq.createmediasystems.blocks.turntable.atlpa2;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/** The audio-techtech AT-LPA2, a clear acrylic turntable that stands in one block (see TurntableBlock). */
public final class TurntableFeature implements Feature {
    public static final DeferredBlock<TurntableBlock> BLOCK = BLOCKS.register("at_lpa2",
            () -> new TurntableBlock(BlockBehaviour.Properties.of().strength(1).sound(SoundType.GLASS).noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);
    public static final Supplier<BlockEntityType<TurntableBlock.Entity>> ENTITY = BLOCK_ENTITIES.register("at_lpa2",
            () -> BlockEntityType.Builder.of(TurntableBlock.Entity::new, BLOCK.get()).build(null));
}
