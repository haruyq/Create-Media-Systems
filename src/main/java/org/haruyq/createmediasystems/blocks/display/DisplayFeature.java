package org.haruyq.createmediasystems.blocks.display;

import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCKS;
import static org.haruyq.createmediasystems.CreateMediaSystems.BLOCK_ENTITIES;
import static org.haruyq.createmediasystems.CreateMediaSystems.ITEMS;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.Feature;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/** Displays that show a Blu-ray player's video, image viewers, and the display quill that links and sizes them. */
public final class DisplayFeature implements Feature {
    /** Display quill: links displays to a Blu-ray player and sets their size (client-side UI only). */
    public static final DeferredItem<Item> DISPLAY_QUILL = ITEMS.register("display_quill",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<DisplayBlock> DISPLAY = BLOCKS.register("display",
            () -> new DisplayBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).strength(1).noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<?> DISPLAY_ITEM = ITEMS.registerSimpleBlockItem("display", DISPLAY);
    public static final DeferredBlock<ImageViewerBlock> IMAGE_VIEWER = BLOCKS.register("image_viewer",
            () -> new ImageViewerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).strength(1).noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<?> IMAGE_VIEWER_ITEM = ITEMS.registerSimpleBlockItem("image_viewer", IMAGE_VIEWER);
    /** Displays'; image viewers saved before they had their own (as displays) load as image viewers. */
    public static final Supplier<BlockEntityType<DisplayBlock.Entity>> DISPLAY_ENTITY = BLOCK_ENTITIES.register(
            "display", DisplayFeature::displayEntity);
    public static final Supplier<BlockEntityType<ImageViewerBlock.Entity>> IMAGE_VIEWER_ENTITY = BLOCK_ENTITIES.register(
            "image_viewer", () -> BlockEntityType.Builder.of(ImageViewerBlock.Entity::new, IMAGE_VIEWER.get()).build(null));

    /**
     * The display type, whose factory gives an image viewer's state an image viewer: vanilla makes a saved block
     * entity by the type it was saved as, and those saved before the split say "display". Only vanilla's loading calls
     * it with such a state, and it takes any block entity.
     */
    @SuppressWarnings("unchecked")
    private static BlockEntityType<DisplayBlock.Entity> displayEntity() {
        BlockEntityType.BlockEntitySupplier<BlockEntity> create = (pos, state) -> state.is(IMAGE_VIEWER.get())
                ? new ImageViewerBlock.Entity(pos, state) : new DisplayBlock.Entity(pos, state);
        return (BlockEntityType<DisplayBlock.Entity>) (BlockEntityType<?>) BlockEntityType.Builder.of(create, DISPLAY.get())
                .build(null);
    }

    /**
     * Pistons leave screens be (see pushReaction), but contraptions move them, and take a screen's cells together:
     * each holds on to the one beside, above or below it of the same kind facing the same way. Screens of a kind side
     * by side on a wall move as one.
     */
    @Override
    public void register(IEventBus modBus) {
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                    state.getBlock() instanceof DisplayBlock ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerAttachedCheck((state, level, pos, direction) -> {
                if (!(state.getBlock() instanceof DisplayBlock) || direction.getAxis() == state.getValue(DisplayBlock.FACING).getAxis()) {
                    return CheckResult.PASS;
                }
                BlockState other = level.getBlockState(pos.relative(direction));
                return other.is(state.getBlock()) && other.getValue(DisplayBlock.FACING) == state.getValue(DisplayBlock.FACING)
                        ? CheckResult.SUCCESS : CheckResult.PASS;
            });
        }));
    }

    @Override
    public void registerPayloads(PayloadRegistrar registrar) {
        DisplayQuill.registerPayloads(registrar);
        ImageViewerBlock.registerPayloads(registrar);
    }
}
