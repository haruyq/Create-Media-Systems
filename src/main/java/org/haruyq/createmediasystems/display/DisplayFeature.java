package org.haruyq.createmediasystems.display;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/** Displays that show a Blu-ray player's video, image viewers, and the display quill that links and sizes them. */
public final class DisplayFeature implements Feature {
    /** Display quill: links displays to a Blu-ray player and sets their size (client-side UI only). */
    public static final DeferredItem<Item> DISPLAY_QUILL = ITEMS.register("display_quill",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<DisplayBlock> DISPLAY = BLOCKS.register("display",
            () -> new DisplayBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).strength(1).noOcclusion()));
    public static final DeferredItem<?> DISPLAY_ITEM = ITEMS.registerSimpleBlockItem("display", DISPLAY);
    public static final DeferredBlock<ImageViewerBlock> IMAGE_VIEWER = BLOCKS.register("image_viewer",
            () -> new ImageViewerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).strength(1).noOcclusion()));
    public static final DeferredItem<?> IMAGE_VIEWER_ITEM = ITEMS.registerSimpleBlockItem("image_viewer", IMAGE_VIEWER);
    /** Displays' and image viewers' (an image viewer is a display that shows an image). */
    public static final Supplier<BlockEntityType<DisplayBlock.Entity>> DISPLAY_ENTITY = BLOCK_ENTITIES.register(
            "display", () -> BlockEntityType.Builder.of(DisplayBlock.Entity::new, DISPLAY.get(), IMAGE_VIEWER.get())
                    .build(null));

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        DisplayQuill.registerPayloads(registrar);
        ImageViewerBlock.registerPayloads(registrar);
    }
}
