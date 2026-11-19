package org.haruyq.createmediasystems.blocks.radio;

import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A radio tower's parts: its mast blocks stack into a column, and the antenna tops it. Right-clicking any of a tower
 * with a mast or an antenna in hand puts it on top, like a Create shaft; a mast goes under an antenna already there.
 */
public final class RadioMastBlock extends Block {
    private static final int EXTEND = PlacementHelpers.register(new Extend());
    private final VoxelShape shape;

    public RadioMastBlock(Properties properties, VoxelShape shape) {
        super(properties);
        this.shape = shape;
    }

    /** The first block above the masts stacked from {@code pos} up: where the tower's antenna is, or goes. */
    static BlockPos top(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        while (level.getBlockState(at).is(RadioFeature.MAST.get())) {
            at.move(Direction.UP);
        }
        return at.immutable();
    }

    /** The antenna of the tower {@code pos} is a part of (a mast of it, or the antenna itself); null without one. */
    @Nullable
    static BlockPos antenna(BlockGetter level, BlockPos pos) {
        BlockPos top = top(level, pos);
        return level.getBlockState(top).is(RadioFeature.ANTENNA.get()) ? top : null;
    }

    /** How many mast blocks stand under the antenna at {@code antenna}. */
    static int masts(BlockGetter level, BlockPos antenna) {
        BlockPos.MutableBlockPos at = antenna.mutable().move(Direction.DOWN);
        int masts = 0;
        while (level.getBlockState(at).is(RadioFeature.MAST.get())) {
            masts++;
            at.move(Direction.DOWN);
        }
        return masts;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        IPlacementHelper helper = PlacementHelpers.get(EXTEND);
        if (!helper.matchesItem(stack) || player.isShiftKeyDown() || !player.mayBuild()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        BlockPos antenna = antenna(level, pos);
        if (antenna == null) {
            return helper.getOffset(player, level, state, pos, hit).placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hit);
        }
        // The tower is finished: a mast goes under its antenna, which moves up one.
        BlockPos above = antenna.above();
        if (!stack.is(RadioFeature.MAST_ITEM.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isOutsideBuildHeight(above) || !level.getBlockState(above).canBeReplaced()) {
            return ItemInteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            level.setBlockAndUpdate(above, level.getBlockState(antenna));
            level.setBlockAndUpdate(antenna, RadioFeature.MAST.get().defaultBlockState());
            SoundType sound = getSoundType(state, level, antenna, player);
            level.playSound(null, antenna, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * .8f);
            stack.consume(1, player);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Create's placement assist for the tower: it shows and places the next block on top, however tall the tower is. */
    private static final class Extend implements IPlacementHelper {
        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.is(RadioFeature.MAST_ITEM.get()) || stack.is(RadioFeature.ANTENNA_ITEM.get());
        }

        @Override
        public Predicate<BlockState> getStatePredicate() {
            return state -> state.getBlock() instanceof RadioMastBlock;
        }

        @Override
        public PlacementOffset getOffset(Player player, Level level, BlockState state, BlockPos pos, BlockHitResult ray) {
            BlockPos top = top(level, pos);
            // Under an antenna the mast goes in by useItemOn; the ghost shows where the tower's top will be.
            if (level.getBlockState(top).is(RadioFeature.ANTENNA.get()) && player.isHolding(RadioFeature.MAST_ITEM.get())) {
                top = top.above();
            }
            return !level.isOutsideBuildHeight(top) && level.getBlockState(top).canBeReplaced() ? PlacementOffset.success(top)
                    : PlacementOffset.fail();
        }
    }
}
