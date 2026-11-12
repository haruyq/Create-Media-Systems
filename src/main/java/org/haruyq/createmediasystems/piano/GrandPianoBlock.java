package org.haruyq.createmediasystems.piano;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class GrandPianoBlock extends BaseEntityBlock implements SableCompat.SilentAssemblyRemoval {
    private static final MapCodec<GrandPianoBlock> CODEC = simpleCodec(GrandPianoBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public enum Part implements StringRepresentable {
        KEYBOARD(0, 0, "keyboard"), RIGHT(1, 0, "right"), BODY(0, 1, "body"),
        BEND(1, 1, "bend"), TAIL(0, 2, "tail"), TIP(1, 2, "tip");

        final int x, z;
        private final String name;

        Part(int x, int z, String name) { this.x = x; this.z = z; this.name = name; }
        @Override public String getSerializedName() { return name; }

        BlockPos at(BlockPos root, Direction facing) {
            return root.relative(facing.getClockWise(), x).relative(facing.getOpposite(), z);
        }
    }

    private static final VoxelShape[][] SHAPES = createShapes();

    public GrandPianoBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.KEYBOARD)
                .setValue(OPEN, false));
    }

    @Override protected MapCodec<GrandPianoBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> states) {
        states.add(FACING, PART, OPEN);
    }

    @Override @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        var root = context.getClickedPos();
        var facing = context.getHorizontalDirection().getOpposite();
        var state = defaultBlockState().setValue(FACING, facing);
        for (Part part : Part.values()) {
            BlockPos pos = part.at(root, facing);
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()
                    || context.getPlayer() != null && !SableCompat.mayInteract(level, context.getPlayer(), pos)
                    || !level.isUnobstructed(null, SHAPES[part.ordinal()][facing.get2DDataValue()]
                            .move(pos.getX(), pos.getY(), pos.getZ()))) return null;
        }
        for (Part leg : new Part[] {Part.KEYBOARD, Part.RIGHT, Part.TAIL}) {
            BlockPos floor = leg.at(root, facing).below();
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return null;
        }
        return state;
    }

    static boolean placeParts(Level level, BlockPos root, BlockState state) {
        Direction facing = state.getValue(FACING);
        Part[] parts = Part.values();
        BlockState[] previous = new BlockState[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Part part = parts[i];
            BlockPos pos = part.at(root, facing);
            previous[i] = level.getBlockState(pos);
            if (previous[i].canBeReplaced() && level.setBlock(pos, state.setValue(PART, part), Block.UPDATE_ALL)) continue;
            for (int placed = i - 1; placed >= 0; placed--) {
                BlockPos at = parts[placed].at(root, facing);
                level.setBlock(at, previous[placed], Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
            }
            return false;
        }
        return true;
    }

    public static BlockPos root(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        Direction facing = state.getValue(FACING);
        return pos.relative(facing.getCounterClockWise(), part.x).relative(facing, part.z);
    }

    public static Vec3 soundboard(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return pos.getCenter().relative(facing.getClockWise(), .4).relative(facing.getOpposite(), .85).add(0, .42, 0);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(root(pos, state)) instanceof PianoBlockEntity piano) {
            if (player.isShiftKeyDown()) {
                if (piano.canOperate(server)) piano.toggleLid();
                else player.displayClientMessage(Component.translatable("piano.busy"), true);
            } else piano.open(server);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.KEYBOARD) {
            BlockPos root = root(pos, state);
            if (isPart(level.getBlockState(root), Part.KEYBOARD, state.getValue(FACING))) level.destroyBlock(root, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !moved && !next.is(this)) {
            Direction facing = state.getValue(FACING);
            BlockPos root = root(pos, state);
            if (state.getValue(PART) == Part.KEYBOARD) {
                for (Part part : Part.values()) {
                    if (part == Part.KEYBOARD) continue;
                    BlockPos at = part.at(root, facing);
                    if (isPart(level.getBlockState(at), part, facing)) level.removeBlock(at, false);
                }
            } else if (isPart(level.getBlockState(root), Part.KEYBOARD, facing)) {
                level.destroyBlock(root, true);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }

    private boolean isPart(BlockState state, Part part, Direction facing) {
        return state.is(this) && state.getValue(PART) == part && state.getValue(FACING) == facing;
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(PART).ordinal()][state.getValue(FACING).get2DDataValue()];
    }

    @Override @Nullable public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.KEYBOARD ? new PianoBlockEntity(pos, state) : null;
    }

    @Override @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(PART) != Part.KEYBOARD) return null;
        return createTickerHelper(type, PianoFeature.PIANO_ENTITY.get(), (world, pos, block, piano) -> piano.tick((ServerLevel) world));
    }

    private static VoxelShape[][] createShapes() {
        // Case, keyboard, the three legs and the pedal lyre, clipped to each occupied block.
        double[][] bounds = {
            {.10, .67, .08, 1.72, .85, .64},
            {.10, .75, .57, 1.72, .99, 1.31},
            {.10, .75, 1.31, 1.49, .99, 1.72},
            {.10, .75, 1.72, 1.13, .99, 2.13},
            {.12, .75, 2.13, .94, .99, 2.57},
            {.29, .75, 2.57, .79, .99, 2.82},
            {.18, .02, .34, .34, .75, .53},
            {1.47, .02, .34, 1.64, .75, .53},
            {.40, .02, 2.43, .58, .76, 2.62},
            {.73, .06, .55, 1.10, .65, .76}
        };
        VoxelShape[][] shapes = new VoxelShape[Part.values().length][4];
        for (Part part : Part.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape shape = Shapes.empty();
                Direction right = facing.getClockWise(), back = facing.getOpposite();
                for (double[] box : bounds) {
                    double x0 = Math.max(0, box[0] - part.x), x1 = Math.min(1, box[3] - part.x);
                    double z0 = Math.max(0, box[2] - part.z), z1 = Math.min(1, box[5] - part.z);
                    if (x0 >= x1 || z0 >= z1) continue;
                    double a = .5 + right.getStepX() * (x0 - .5) + back.getStepX() * (z0 - .5);
                    double b = .5 + right.getStepZ() * (x0 - .5) + back.getStepZ() * (z0 - .5);
                    double c = .5 + right.getStepX() * (x1 - .5) + back.getStepX() * (z1 - .5);
                    double d = .5 + right.getStepZ() * (x1 - .5) + back.getStepZ() * (z1 - .5);
                    shape = Shapes.or(shape, Shapes.box(Math.min(a, c), box[1], Math.min(b, d),
                            Math.max(a, c), box[4], Math.max(b, d)));
                }
                shapes[part.ordinal()][facing.get2DDataValue()] = shape;
            }
        }
        return shapes;
    }
}
