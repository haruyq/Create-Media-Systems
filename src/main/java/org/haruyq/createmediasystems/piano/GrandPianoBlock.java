package org.haruyq.createmediasystems.piano;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.contraption.transformable.TransformableBlock;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

public final class GrandPianoBlock extends BaseEntityBlock implements SableCompat.SilentAssemblyRemoval, TransformableBlock {
    private static final MapCodec<GrandPianoBlock> CODEC = simpleCodec(GrandPianoBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final DirectionProperty UP = DirectionProperty.create("up");
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public enum Part implements StringRepresentable {
        KEYBOARD(0, 0, "keyboard"), RIGHT(1, 0, "right"), BODY(0, 1, "body"),
        BEND(1, 1, "bend"), TAIL(0, 2, "tail"), TIP(1, 2, "tip");

        final int x, z;
        private final String name;

        Part(int x, int z, String name) { this.x = x; this.z = z; this.name = name; }
        @Override public String getSerializedName() { return name; }

        public BlockPos at(BlockPos root, BlockState state) {
            return root.relative(right(state), x).relative(state.getValue(FACING).getOpposite(), z);
        }
    }

    private static final VoxelShape[][][] SHAPES = createShapes();

    public GrandPianoBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.KEYBOARD)
                .setValue(UP, Direction.UP).setValue(OPEN, false));
    }

    @Override protected MapCodec<GrandPianoBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> states) {
        states.add(FACING, UP, PART, OPEN);
    }

    @Override @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        var root = context.getClickedPos();
        var facing = context.getHorizontalDirection().getOpposite();
        var state = defaultBlockState().setValue(FACING, facing);
        for (Part part : Part.values()) {
            BlockPos pos = part.at(root, state);
            BlockPos inWorld = BlockPos.containing(SableCompat.toWorld(level, pos, pos.getCenter()));
            if (!SableCompat.sameSubLevel(level, root, pos)
                    || !level.isInWorldBounds(inWorld) || !level.getWorldBorder().isWithinBounds(inWorld)
                    || !level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()
                    || context.getPlayer() != null && !SableCompat.mayInteract(level, context.getPlayer(), pos)
                    || !level.isUnobstructed(null, SHAPES[part.ordinal()][facing.ordinal()][Direction.UP.ordinal()]
                            .move(pos.getX(), pos.getY(), pos.getZ()))) return null;
        }
        for (Part leg : new Part[] {Part.KEYBOARD, Part.RIGHT, Part.TAIL}) {
            BlockPos floor = leg.at(root, state).below();
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return null;
        }
        return state;
    }

    static boolean placeParts(Level level, BlockPos root, BlockState state) {
        Part[] parts = Part.values();
        BlockState[] previous = new BlockState[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Part part = parts[i];
            BlockPos pos = part.at(root, state);
            previous[i] = level.getBlockState(pos);
            if (previous[i].canBeReplaced() && level.setBlock(pos, state.setValue(PART, part), Block.UPDATE_ALL)) continue;
            for (int placed = i - 1; placed >= 0; placed--) {
                BlockPos at = parts[placed].at(root, state);
                level.setBlock(at, previous[placed], Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
            }
            return false;
        }
        return true;
    }

    public static BlockPos root(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        return pos.relative(right(state).getOpposite(), part.x).relative(state.getValue(FACING), part.z);
    }

    public static Direction up(BlockState state) {
        Direction facing = state.getValue(FACING), up = state.getValue(UP);
        if (facing.getAxis() != up.getAxis()) return up;
        return facing.getAxis().isVertical() ? Direction.SOUTH : Direction.UP;
    }

    public static Direction right(BlockState state) {
        return right(state.getValue(FACING), up(state));
    }

    private static Direction right(Direction facing, Direction up) {
        return Direction.fromDelta(facing.getStepY() * up.getStepZ() - facing.getStepZ() * up.getStepY(),
                facing.getStepZ() * up.getStepX() - facing.getStepX() * up.getStepZ(),
                facing.getStepX() * up.getStepY() - facing.getStepY() * up.getStepX());
    }

    public static Vec3 soundboard(BlockPos pos, BlockState state) {
        return pos.getCenter().relative(right(state), .4).relative(state.getValue(FACING).getOpposite(), .85)
                .relative(up(state), .42);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(root(pos, state)) instanceof PianoBlockEntity piano) {
            piano.interact(server, player.isShiftKeyDown());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.KEYBOARD) {
            BlockPos root = root(pos, state);
            if (isPart(level.getBlockState(root), Part.KEYBOARD, state)) level.destroyBlock(root, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !moved && !next.is(this)) {
            BlockPos root = root(pos, state);
            if (state.getValue(PART) == Part.KEYBOARD) {
                for (Part part : Part.values()) {
                    if (part == Part.KEYBOARD) continue;
                    BlockPos at = part.at(root, state);
                    if (isPart(level.getBlockState(at), part, state)) level.removeBlock(at, false);
                }
            } else if (isPart(level.getBlockState(root), Part.KEYBOARD, state)) {
                level.destroyBlock(root, true);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }

    private boolean isPart(BlockState candidate, Part part, BlockState state) {
        return candidate.is(this) && candidate.getValue(PART) == part
                && candidate.getValue(FACING) == state.getValue(FACING) && up(candidate) == up(state);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING))).setValue(UP, rotation.rotate(up(state)));
    }

    @Override
    public BlockState transform(BlockState state, StructureTransform transform) {
        return state.setValue(FACING, transform.rotateFacing(state.getValue(FACING))).setValue(UP, transform.rotateFacing(up(state)));
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(PART).ordinal()][state.getValue(FACING).ordinal()][up(state).ordinal()];
    }

    @Override @Nullable public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.KEYBOARD ? new PianoBlockEntity(pos, state) : null;
    }

    @Override @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(PART) != Part.KEYBOARD) return null;
        return createTickerHelper(type, PianoFeature.PIANO_ENTITY.get(), (world, pos, block, piano) -> piano.tick());
    }

    private static VoxelShape[][][] createShapes() {
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
        int directions = Direction.values().length;
        VoxelShape[][][] shapes = new VoxelShape[Part.values().length][directions][directions];
        for (Part part : Part.values()) {
            for (Direction facing : Direction.values()) {
                for (Direction up : Direction.values()) {
                    if (up.getAxis() == facing.getAxis()) continue;
                    VoxelShape shape = Shapes.empty();
                    Direction right = right(facing, up), back = facing.getOpposite();
                    for (double[] box : bounds) {
                        double x0 = Math.max(0, box[0] - part.x), x1 = Math.min(1, box[3] - part.x);
                        double z0 = Math.max(0, box[2] - part.z), z1 = Math.min(1, box[5] - part.z);
                        if (x0 >= x1 || z0 >= z1) continue;
                        var a = corner(right, up, back, x0, box[1], z0);
                        var b = corner(right, up, back, x1, box[4], z1);
                        shape = Shapes.or(shape, Shapes.box(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
                                Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)));
                    }
                    shapes[part.ordinal()][facing.ordinal()][up.ordinal()] = shape;
                }
            }
        }
        return shapes;
    }

    private static Vec3 corner(Direction right, Direction up, Direction back, double x, double y, double z) {
        return new Vec3(.5, .5, .5).relative(right, x - .5).relative(up, y - .5).relative(back, z - .5);
    }
}
