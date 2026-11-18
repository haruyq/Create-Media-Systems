package org.haruyq.createmediasystems.screen;

import org.haruyq.createmediasystems.SableCompat;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Monitor a Media Server needs on top to share: it shows the share there, its host's capture to its host
 * and the share as it comes over P2P to everyone else (see ScreenMonitor). Right-clicked, it opens the server's screen.
 */
public class ScreenMonitorBlock extends HorizontalDirectionalBlock implements SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<ScreenMonitorBlock> CODEC = simpleCodec(ScreenMonitorBlock::new);
    /** Facing north, as the model is: its panel, its stand and its foot. */
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        VoxelShape north = Shapes.or(box(0, 4, 7, 16, 13.5, 8.5), box(4, 6, 8.5, 12, 12, 9.5), box(7, 1, 9, 9, 7, 10),
                box(4, 0, 5, 12, 1, 11));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            SHAPES[facing.get2DDataValue()] = rotate(north, facing);
        }
    }

    public ScreenMonitorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<ScreenMonitorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Facing as the server below does, if it stands on one. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        Direction facing = below.getBlock() instanceof ScreenServerBlock ? below.getValue(FACING)
                : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockState below = level.getBlockState(pos.below());
        return below.getBlock() instanceof ScreenServerBlock server
                ? server.useWithoutItem(below, level, pos.below(), player, hit) : InteractionResult.PASS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    /** {@code north} turned to face {@code facing}, as the blockstate turns the model. */
    private static VoxelShape rotate(VoxelShape north, Direction facing) {
        VoxelShape[] turned = {Shapes.empty()};
        north.forAllBoxes((x0, y0, z0, x1, y1, z1) -> turned[0] = Shapes.or(turned[0], switch (facing) {
            case EAST -> Shapes.box(1 - z1, y0, x0, 1 - z0, y1, x1);
            case SOUTH -> Shapes.box(1 - x1, y0, 1 - z1, 1 - x0, y1, 1 - z0);
            case WEST -> Shapes.box(z0, y0, 1 - x1, z1, y1, 1 - x0);
            default -> Shapes.box(x0, y0, z0, x1, y1, z1);
        }));
        return turned[0];
    }
}
