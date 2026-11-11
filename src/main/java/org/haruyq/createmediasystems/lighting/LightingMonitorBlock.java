package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.SableCompat;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class LightingMonitorBlock extends HorizontalDirectionalBlock implements SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<LightingMonitorBlock> CODEC = simpleCodec(LightingMonitorBlock::new);
    public static final IntegerProperty COLUMN = LightingConsoleBlock.COLUMN;
    public static final BooleanProperty FORMED = LightingConsoleBlock.FORMED;

    public LightingMonitorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(COLUMN, 0)
                .setValue(FORMED, false));
    }

    @Override
    protected MapCodec<LightingMonitorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COLUMN, FORMED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        Direction facing = below.getBlock() instanceof LightingConsoleBlock ? below.getValue(FACING)
                : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    /** Has the desk below check whether the console is complete now, as a placed desk does. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!state.is(oldState.getBlock()) && level.getBlockState(pos.below()).getBlock() instanceof LightingConsoleBlock desk) {
            level.scheduleTick(pos.below(), desk, 1);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            LightingConsoleBlock.recheck(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> box(0, 0, 2, 16, 16, 8);
            case WEST -> box(8, 0, 0, 14, 16, 16);
            case EAST -> box(2, 0, 0, 8, 16, 16);
            default -> box(0, 0, 8, 16, 16, 14);
        };
    }
}
