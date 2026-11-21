package org.haruyq.createmediasystems.items.instrument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The footswitch and three-position knob are shared by every kind of pedal. */
public final class AutoPedalBlock extends Block {
    private static final IntegerProperty DEPTH = IntegerProperty.create("depth", 1, 3);
    private static final VoxelShape NORTH_SOUTH = box(3.5, 0, 1.5, 12.5, 4, 14.5);
    private static final VoxelShape EAST_WEST = box(1.5, 0, 3.5, 14.5, 4, 12.5);
    private final AutoEffect effect;

    AutoPedalBlock(AutoEffect effect, Properties properties) {
        super(properties);
        this.effect = effect;
        registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.LIT, true).setValue(DEPTH, 2));
    }

    public AutoEffect effect() {
        return effect;
    }

    /** 0 is bypass, 1–3 are increasing effect strengths. */
    public int amount(BlockState state) {
        return state.getValue(BlockStateProperties.LIT) ? state.getValue(DEPTH) : 0;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, BlockStateProperties.LIT, DEPTH);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(HorizontalDirectionalBlock.FACING, rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(HorizontalDirectionalBlock.FACING).getAxis() == Direction.Axis.X ? EAST_WEST : NORTH_SOUTH;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hit) {
        return stack.isEmpty() && player.getOffhandItem().isEmpty()
                ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockState next;
            Component status;
            if (player.isShiftKeyDown()) {
                next = state.cycle(DEPTH);
                status = Component.translatable("guitar_patch.create_media_systems.depth", effect.displayName(), next.getValue(DEPTH));
            } else {
                next = state.cycle(BlockStateProperties.LIT);
                String key = next.getValue(BlockStateProperties.LIT) ? "enabled" : "bypassed";
                status = Component.translatable("guitar_patch.create_media_systems." + key, effect.displayName());
            }
            level.setBlockAndUpdate(pos, next);
            player.displayClientMessage(status, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
