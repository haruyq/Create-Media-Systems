package org.haruyq.createmediasystems.blocks.dj.ddjflx4;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/** The PIONEAR DJ DDJ-FLX4, a DJ controller that follows a player's own and their rekordbox (see DdjFlx4Block). */
public final class DjFeature implements Feature {
    public static final DeferredBlock<DdjFlx4Block> BLOCK = BLOCKS.register("ddj_flx4",
            () -> new DdjFlx4Block(BlockBehaviour.Properties.of().strength(1).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);
    public static final Supplier<BlockEntityType<DdjFlx4Block.Entity>> ENTITY = BLOCK_ENTITIES.register("ddj_flx4",
            () -> BlockEntityType.Builder.of(DdjFlx4Block.Entity::new, BLOCK.get()).build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        DdjFlx4Block.registerPayloads(registrar);
    }
}
