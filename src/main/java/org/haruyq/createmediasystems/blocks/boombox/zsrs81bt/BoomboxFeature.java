package org.haruyq.createmediasystems.blocks.boombox.zsrs81bt;

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

/** The Sunny ZS-RS81BT, a CD radio that stands in one block (see BoomboxBlock). */
public final class BoomboxFeature implements Feature {
    public static final DeferredBlock<BoomboxBlock> BLOCK = BLOCKS.register("zs_rs81bt",
            () -> new BoomboxBlock(BlockBehaviour.Properties.of().strength(1).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);
    public static final Supplier<BlockEntityType<BoomboxBlock.Entity>> ENTITY = BLOCK_ENTITIES.register("zs_rs81bt",
            () -> BlockEntityType.Builder.of(BoomboxBlock.Entity::new, BLOCK.get()).build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(BoomboxBlock.Press.TYPE, BoomboxBlock.Press.CODEC, BoomboxBlock.Press::handle);
    }
}
