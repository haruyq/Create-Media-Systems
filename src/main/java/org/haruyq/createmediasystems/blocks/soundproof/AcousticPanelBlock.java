package org.haruyq.createmediasystems.blocks.soundproof;

import java.util.EnumMap;
import java.util.Map;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A panel of wedged acoustic foam, three pixels thick on the wall, floor or ceiling it was put on; it faces away from
 * that. Its wedges run one way or, {@link #CROSS}, the other: put up, they alternate as tiles do, and a wrench turns them.
 */
public final class AcousticPanelBlock extends DirectionalBlock implements IWrenchable {
    public static final BooleanProperty CROSS = BooleanProperty.create("cross");
    private static final MapCodec<AcousticPanelBlock> CODEC = simpleCodec(AcousticPanelBlock::new);
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.UP, box(0, 0, 0, 16, 3, 16), Direction.DOWN, box(0, 13, 0, 16, 16, 16),
            Direction.NORTH, box(0, 0, 13, 16, 16, 16), Direction.SOUTH, box(0, 0, 0, 16, 16, 3),
            Direction.WEST, box(13, 0, 0, 16, 16, 16), Direction.EAST, box(0, 0, 0, 3, 16, 16)));

    public AcousticPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.UP).setValue(CROSS, false));
    }

    @Override
    protected MapCodec<AcousticPanelBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CROSS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        return defaultBlockState().setValue(FACING, context.getClickedFace())
                .setValue(CROSS, (pos.getX() + pos.getY() + pos.getZ() & 1) == 1);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide) {
            level.setBlock(context.getClickedPos(), state.cycle(CROSS), UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** On a floor or ceiling a quarter turn about the vertical turns its wedges too; on a wall they stay as they ran. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        boolean quarter = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        BlockState turned = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        return quarter && state.getValue(FACING).getAxis() == Direction.Axis.Y ? turned.cycle(CROSS) : turned;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
