package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;
import org.haruyq.createmediasystems.blocks.piano.PianoPlacement;
import org.jetbrains.annotations.Nullable;

public final class GrandPianoBlock extends BaseEntityBlock implements SableCompat.SilentAssemblyRemoval, TransformableBlock {
    private static final MapCodec<GrandPianoBlock> CODEC = simpleCodec(GrandPianoBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final DirectionProperty UP = DirectionProperty.create("up");
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty KEYBOARD_OPEN = BooleanProperty.create("keyboard_open");
    public static final BooleanProperty FULL_SIZE = BooleanProperty.create("full_size");
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final float KEY_LEFT = .275f, KEY_RIGHT = 1.545f;
    public static final float FULL_SCALE = (Layout.KEYS_RIGHT - Layout.KEYS_LEFT) / (16 * (KEY_RIGHT - KEY_LEFT));
    private static final double CENTRE = (KEY_LEFT + KEY_RIGHT) / 2.0;

    public enum Part implements StringRepresentable {
        KEYBOARD(0, 0), RIGHT(1, 0), BODY(0, 1), BEND(1, 1), TAIL(0, 2), TIP(1, 2),
        LEFT_LEG(-1, 0), RIGHT_LEG(2, 0), LEFT_BRACE(-1, 1), RIGHT_BRACE(2, 1),
        TAIL_FOOT_LEFT(-1, 0, 4), TAIL_FOOT(0, 0, 4), TAIL_LEG_LEFT(-1, 0, 5), TAIL_LEG(0, 0, 5),
        TOP_L0(-1, 1, 0), TOP_C0(0, 1, 0), TOP_R0(1, 1, 0), TOP_E0(2, 1, 0),
        TOP_L1(-1, 1, 1), TOP_C1(0, 1, 1), TOP_R1(1, 1, 1), TOP_E1(2, 1, 1),
        TOP_L2(-1, 1, 2), TOP_C2(0, 1, 2), TOP_R2(1, 1, 2), TOP_E2(2, 1, 2),
        TOP_L3(-1, 1, 3), TOP_C3(0, 1, 3), TOP_R3(1, 1, 3), TOP_E3(2, 1, 3),
        TOP_L4(-1, 1, 4), TOP_C4(0, 1, 4), TOP_R4(1, 1, 4),
        TOP_L5(-1, 1, 5), TOP_C5(0, 1, 5);

        final int x, y, z;

        Part(int x, int z) { this(x, 0, z); }

        Part(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }

        public BlockPos at(BlockPos root, BlockState state) {
            return root.relative(right(state), x).relative(up(state), y).relative(state.getValue(FACING).getOpposite(), z);
        }
    }

    private static final List<Part> COMPACT_PARTS = List.of(Part.KEYBOARD, Part.RIGHT, Part.BODY, Part.BEND, Part.TAIL, Part.TIP);
    private static final List<Part> COMPACT_SUPPORTS = List.of(Part.KEYBOARD, Part.RIGHT, Part.TAIL);
    private static final List<Part> FULL_SUPPORTS = List.of(Part.LEFT_LEG, Part.RIGHT_LEG, Part.TAIL_LEG);
    private static final double[] CLOSED_COVER_BOUNDS = {.272, .818, .118, 1.548, .891, .323};
    private static final double[] OPEN_COVER_BOUNDS = {.272, .856, .284, 1.548, 1.062, .387};
    private static final VoxelShape[][][] COMPACT_SHAPES = createShapes(false, null);
    private static final VoxelShape[][][] FULL_SHAPES = createShapes(true, null);
    private static final VoxelShape[][][] COMPACT_CLOSED = createShapes(false, CLOSED_COVER_BOUNDS);
    private static final VoxelShape[][][] FULL_CLOSED = createShapes(true, CLOSED_COVER_BOUNDS);
    private static final VoxelShape[][][] COMPACT_OPEN = createShapes(false, OPEN_COVER_BOUNDS);
    private static final VoxelShape[][][] FULL_OPEN = createShapes(true, OPEN_COVER_BOUNDS);
    private static final List<Part> FULL_PARTS = Arrays.stream(Part.values())
            .filter(part -> part == Part.KEYBOARD || !FULL_SHAPES[part.ordinal()][Direction.NORTH.ordinal()][Direction.UP.ordinal()].isEmpty())
            .toList();

    public GrandPianoBlock(Properties properties) {
        super(properties);
        // Saves with no full_size property still load as the original six-part grand.
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.KEYBOARD)
                .setValue(UP, Direction.UP).setValue(OPEN, false).setValue(KEYBOARD_OPEN, true).setValue(FULL_SIZE, false));
    }

    @Override protected MapCodec<GrandPianoBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> states) {
        states.add(FACING, UP, PART, OPEN, KEYBOARD_OPEN, FULL_SIZE);
    }

    @Override @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        var root = context.getClickedPos();
        var facing = context.getHorizontalDirection().getOpposite();
        var state = defaultBlockState().setValue(FACING, facing).setValue(FULL_SIZE, true);
        return supported(level, root, state) && fits(level, root, null, state, context.getPlayer()) ? state : null;
    }

    public static List<Part> parts(BlockState state) {
        return state.getValue(FULL_SIZE) ? FULL_PARTS : COMPACT_PARTS;
    }

    private static boolean supported(Level level, BlockPos root, BlockState state) {
        var supports = state.getValue(FULL_SIZE) ? FULL_SUPPORTS : COMPACT_SUPPORTS;
        return supports.stream().allMatch(part -> PianoPlacement.supported(level, part.at(root, state), up(state)));
    }

    private static boolean fits(Level level, BlockPos root, @Nullable BlockState before, BlockState state, @Nullable Player player) {
        for (var part : parts(state)) {
            var pos = part.at(root, state);
            if (!PianoPlacement.fits(level, root, pos, shape(state.setValue(PART, part)), player)) return false;
            var current = level.getBlockState(pos);
            boolean belongs = before != null && current.is(before.getBlock())
                    && isPart(current, current.getValue(PART), before) && root(pos, current).equals(root);
            if (!belongs && (!current.canBeReplaced() || !level.getFluidState(pos).isEmpty())) return false;
        }
        return true;
    }

    static boolean placeParts(Level level, BlockPos root, BlockState state) {
        return fits(level, root, null, state, null) && PianoPlacement.install(level, states(root, state));
    }

    private static Map<BlockPos, BlockState> states(BlockPos root, BlockState state) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (var part : parts(state)) blocks.put(part.at(root, state), state.setValue(PART, part));
        return blocks;
    }

    private static void upgrade(Level level, BlockPos root, BlockState before) {
        for (var part : COMPACT_PARTS) {
            var at = part.at(root, before);
            if (!level.isLoaded(at) || !isPart(level.getBlockState(at), part, before)) return;
        }
        var expanded = before.setValue(FULL_SIZE, true);
        for (var part : parts(expanded)) {
            var at = part.at(root, expanded);
            if (!level.isLoaded(at)) return;
            var current = level.getBlockState(at);
            if (!current.isAir() && (!current.is(before.getBlock()) || !root(at, current).equals(root))) return;
        }
        if (!supported(level, root, expanded) || !fits(level, root, before, expanded, null)) return;
        var blocks = states(root, expanded);
        if (!PianoPlacement.install(level, blocks)) return;
        for (var part : COMPACT_PARTS) {
            var at = part.at(root, before);
            if (!blocks.containsKey(at) && isPart(level.getBlockState(at), part, before)) level.removeBlock(at, true);
        }
    }

    public static BlockPos root(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        return pos.relative(right(state), -part.x).relative(up(state), -part.y).relative(state.getValue(FACING), part.z);
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
        var local = modelToLocal(new Vec3(.9, .92, 1.35), state.getValue(FULL_SIZE));
        return corner(right(state), up(state), state.getValue(FACING).getOpposite(), local.x, local.y, local.z)
                .add(Vec3.atLowerCornerOf(pos));
    }

    public static Vec3 modelToLocal(Vec3 model, boolean fullSize) {
        return fullSize ? new Vec3(scaleX(model.x), model.y * FULL_SCALE, model.z * FULL_SCALE) : model;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(root(pos, state)) instanceof PianoBlockEntity piano) {
            piano.interact(server, interaction(player, state.getValue(PART), piano.getBlockState()));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static Action interaction(Player player, Part part, BlockState root) {
        if (player.isShiftKeyDown()) {
            return part.z == 0 ? Action.KEYBOARD_LID : Action.ROOF;
        }
        return root.getValue(KEYBOARD_OPEN) ? Action.PLAY : Action.KEYBOARD_LID;
    }

    static BlockState moveLid(BlockState state, Action action) {
        return state.cycle(action == Action.ROOF ? OPEN : KEYBOARD_OPEN);
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
                for (Part part : parts(state)) {
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

    private static boolean isPart(BlockState candidate, Part part, BlockState state) {
        return candidate.is(state.getBlock()) && candidate.getValue(PART) == part
                && candidate.getValue(FACING) == state.getValue(FACING) && up(candidate) == up(state)
                && candidate.getValue(FULL_SIZE).equals(state.getValue(FULL_SIZE));
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING))).setValue(UP, rotation.rotate(up(state)));
    }

    @Override
    public BlockState transform(BlockState state, StructureTransform transform) {
        return state.setValue(FACING, transform.rotateFacing(state.getValue(FACING))).setValue(UP, transform.rotateFacing(up(state)));
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        var part = state.getValue(PART);
        if (part.z != 0) return shape(state);
        var main = level.getBlockState(root(pos, state));
        if (!isPart(main, Part.KEYBOARD, state)) return shape(state);
        boolean full = state.getValue(FULL_SIZE), open = main.getValue(KEYBOARD_OPEN);
        var shapes = full ? (open ? FULL_OPEN : FULL_CLOSED) : (open ? COMPACT_OPEN : COMPACT_CLOSED);
        return shape(state, shapes);
    }

    private static VoxelShape shape(BlockState state) {
        return shape(state, state.getValue(FULL_SIZE) ? FULL_SHAPES : COMPACT_SHAPES);
    }

    private static VoxelShape shape(BlockState state, VoxelShape[][][] shapes) {
        return shapes[state.getValue(PART).ordinal()][state.getValue(FACING).ordinal()][up(state).ordinal()];
    }

    @Override @Nullable public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.KEYBOARD ? new PianoBlockEntity(pos, state) : null;
    }

    @Override @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(PART) != Part.KEYBOARD) return null;
        return createTickerHelper(type, PianoFeature.PIANO_ENTITY.get(), (world, pos, block, piano) -> {
            if (!block.getValue(FULL_SIZE) && world.getGameTime() % 20 == 0 && !SableCompat.inPlotGrid(world, pos)) {
                upgrade(world, pos, block);
            }
            piano.tick();
        });
    }

    private static double scaleX(double x) { return CENTRE + (x - CENTRE) * FULL_SCALE; }

    private static VoxelShape[][][] createShapes(boolean fullSize, @Nullable double[] cover) {
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
        if (cover != null) {
            bounds = Arrays.copyOf(bounds, bounds.length + 1);
            bounds[bounds.length - 1] = cover.clone();
        }
        if (fullSize) {
            for (var box : bounds) {
                box[0] = scaleX(box[0]);
                box[1] *= FULL_SCALE;
                box[2] *= FULL_SCALE;
                box[3] = scaleX(box[3]);
                box[4] = Math.min(2, box[4] * FULL_SCALE);
                box[5] *= FULL_SCALE;
            }
        }
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
                        double y0 = Math.max(0, box[1] - part.y), y1 = Math.min(1, box[4] - part.y);
                        if (x0 >= x1 || y0 >= y1 || z0 >= z1) continue;
                        var a = corner(right, up, back, x0, y0, z0);
                        var b = corner(right, up, back, x1, y1, z1);
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
