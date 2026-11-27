package org.haruyq.createmediasystems.blocks.soundproof;

import java.util.Map;
import java.util.function.UnaryOperator;

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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Panels of wedged acoustic foam, each three pixels thick on a wall, floor or ceiling, facing away from it. One block
 * holds a panel on each of its sides ({@link #ON}), so that the panels of two walls meet in a corner. Their wedges run
 * one way or, {@link #CROSS}, the other: put up, they alternate as tiles do, and a wrench turns them.
 */
public final class AcousticPanelBlock extends Block implements IWrenchable {
    public static final BooleanProperty CROSS = BooleanProperty.create("cross");
    /** Whether a panel lies against the side of the block in that direction. */
    public static final Map<Direction, BooleanProperty> ON = PipeBlock.PROPERTY_BY_DIRECTION;
    private static final MapCodec<AcousticPanelBlock> CODEC = simpleCodec(AcousticPanelBlock::new);
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.DOWN, box(0, 0, 0, 16, 3, 16), Direction.UP, box(0, 13, 0, 16, 16, 16),
            Direction.SOUTH, box(0, 0, 13, 16, 16, 16), Direction.NORTH, box(0, 0, 0, 16, 16, 3),
            Direction.EAST, box(13, 0, 0, 16, 16, 16), Direction.WEST, box(0, 0, 0, 3, 16, 16));
    private final Map<BlockState, VoxelShape> shapes;

    public AcousticPanelBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(CROSS, false);
        for (BooleanProperty on : ON.values()) {
            state = state.setValue(on, false);
        }
        // Never without a panel: a block saved with none (one from before panels shared a block) is on the floor.
        registerDefaultState(state.setValue(ON.get(Direction.DOWN), true));
        shapes = getShapeForEachState(s -> ON.entrySet().stream().filter(e -> s.getValue(e.getValue()))
                .map(e -> SHAPES.get(e.getKey())).reduce(Shapes.empty(), Shapes::or));
    }

    /** A single panel against the side of the block in direction {@code side}. */
    public BlockState against(Direction side) {
        return defaultBlockState().setValue(ON.get(Direction.DOWN), false).setValue(ON.get(side), true);
    }

    @Override
    protected MapCodec<AcousticPanelBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CROSS);
        ON.values().forEach(builder::add);
    }

    /** A panel goes into a block that has some, against a full side clicked that has none yet. */
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        Direction side = context.getClickedFace().getOpposite();
        BlockPos wall = context.getClickedPos().relative(side);
        return context.getItemInHand().is(asItem()) && !state.getValue(ON.get(side))
                && MultifaceBlock.canAttachTo(context.getLevel(), side, wall, context.getLevel().getBlockState(wall));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        BlockState here = context.getLevel().getBlockState(pos);
        Direction side = context.getClickedFace().getOpposite();
        return here.is(this) ? here.setValue(ON.get(side), true)
                : against(side).setValue(CROSS, (pos.getX() + pos.getY() + pos.getZ() & 1) == 1);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide) {
            level.setBlock(context.getClickedPos(), state.cycle(CROSS), UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * On a floor or ceiling a quarter turn about the vertical turns the wedges too; on a wall they stay as they ran.
     * ponytail: one CROSS for the block, so a wall panel sharing a block with a floor one turns its wedges with it. A
     * CROSS per side if that shows.
     */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        boolean quarter = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        BlockState turned = map(state, rotation::rotate);
        return quarter && (state.getValue(ON.get(Direction.UP)) || state.getValue(ON.get(Direction.DOWN)))
                ? turned.cycle(CROSS) : turned;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return map(state, mirror::mirror);
    }

    private static BlockState map(BlockState state, UnaryOperator<Direction> to) {
        BlockState mapped = state;
        for (Map.Entry<Direction, BooleanProperty> on : ON.entrySet()) {
            mapped = mapped.setValue(ON.get(to.apply(on.getKey())), state.getValue(on.getValue()));
        }
        return mapped;
    }
}
