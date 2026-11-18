package org.haruyq.createmediasystems.screen;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * The screen share server, which shares its user's window or monitor with the Blu-ray Player beside it, and the monitor
 * it needs on top, which shows the share.
 */
public final class ScreenFeature implements Feature {
    public static final DeferredBlock<ScreenServerBlock> SERVER = BLOCKS.register("screen_share_server",
            () -> new ScreenServerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
    public static final DeferredItem<?> SERVER_ITEM = ITEMS.registerSimpleBlockItem("screen_share_server", SERVER);
    public static final Supplier<BlockEntityType<ScreenServerBlock.Entity>> SERVER_ENTITY = BLOCK_ENTITIES.register(
            "screen_share_server", () -> BlockEntityType.Builder.of(ScreenServerBlock.Entity::new, SERVER.get()).build(null));
    public static final DeferredBlock<ScreenMonitorBlock> MONITOR = BLOCKS.register("screen_share_monitor",
            () -> new ScreenMonitorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final DeferredItem<?> MONITOR_ITEM = ITEMS.registerSimpleBlockItem("screen_share_monitor", MONITOR);

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        ScreenServerBlock.registerPayloads(registrar);
    }
}
