package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.contraption.transformable.TransformableBlock;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.PianoPlacement;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets.Target;
import org.haruyq.createmediasystems.items.power.BatteryItem;
import org.jetbrains.annotations.Nullable;

public final class KeyboardBlock extends BaseEntityBlock implements IWrenchable, TransformableBlock, SableCompat.SilentAssemblyRemoval {
    public enum Part implements StringRepresentable {
        MAIN(0, 0), BASS(1, 0), TREBLE(-1, 0), TOP(0, 1), TOP_BASS(1, 1), TOP_TREBLE(-1, 1);

        final int x, y;

        Part(int x, int y) { this.x = x; this.y = y; }

        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }

        public BlockPos at(BlockPos root, BlockState state) {
            return root.relative(right(state), x).relative(up(state), y);
        }
    }

    private static final List<Part> COMPACT_PARTS = List.of(Part.MAIN, Part.BASS);
    private static final List<Part> TABLETOP_PARTS = List.of(Part.MAIN, Part.BASS, Part.TREBLE);
    private static final List<Part> STAND_PARTS = List.of(Part.values());
    private static final Vec3 MODEL_CENTRE = new Vec3(1, 0, .5);
    private static final Vec3 CENTRE = new Vec3(.5, 0, .5);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final DirectionProperty UP = DirectionProperty.create("up");
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty STAND = BooleanProperty.create("stand"), RACK = BooleanProperty.create("rack");
    public static final BooleanProperty FULL_SIZE = BooleanProperty.create("full_size");
    private static final MapCodec<KeyboardBlock> CODEC = simpleCodec(KeyboardBlock::new);
    private static final Map<BlockState, VoxelShape> COLLISION = new java.util.concurrent.ConcurrentHashMap<>();

    KeyboardBlock(Properties properties) {
        super(properties);
        // Saves with no full_size property still load as the original two-part keyboard.
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(UP, Direction.UP)
                .setValue(PART, Part.MAIN).setValue(STAND, true).setValue(RACK, true).setValue(FULL_SIZE, false));
    }

    @Override protected MapCodec<KeyboardBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, PART, STAND, RACK, FULL_SIZE);
    }

    public static List<Part> parts(BlockState state) {
        if (!state.getValue(FULL_SIZE)) return COMPACT_PARTS;
        return state.getValue(STAND) ? STAND_PARTS : TABLETOP_PARTS;
    }

    public static BlockPos root(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        return pos.relative(right(state), -part.x).relative(up(state), -part.y);
    }

    public static Vec3 point(BlockPos root, BlockState state, Vec3 local) {
        return orient(root, state, modelToLocal(state, local));
    }

    public static Vec3 modelToLocal(BlockState state, Vec3 model) {
        return state.getValue(FULL_SIZE) ? model.subtract(MODEL_CENTRE).scale(KeyboardLayout.PX.fullScale).add(CENTRE) : model;
    }

    private static Vec3 orient(BlockPos pos, BlockState state, Vec3 local) {
        return pos.getCenter().relative(right(state), local.x - .5).relative(up(state), local.y - .5)
                .relative(state.getValue(FACING).getOpposite(), local.z - .5);
    }

    static Vec3 local(BlockPos root, BlockState state, Vec3 point) {
        var d = point.subtract(root.getCenter());
        var local = new Vec3(.5 + dot(d, right(state)), .5 + dot(d, up(state)),
                .5 + dot(d, state.getValue(FACING).getOpposite()));
        return state.getValue(FULL_SIZE) ? local.subtract(CENTRE).scale(1.0 / KeyboardLayout.PX.fullScale).add(MODEL_CENTRE) : local;
    }

    public static Direction up(BlockState state) {
        Direction facing = state.getValue(FACING), up = state.getValue(UP);
        if (facing.getAxis() != up.getAxis()) return up;
        return facing.getAxis().isVertical() ? Direction.SOUTH : Direction.UP;
    }

    public static Direction right(BlockState state) {
        var facing = state.getValue(FACING);
        var up = up(state);
        return Direction.fromDelta(facing.getStepY() * up.getStepZ() - facing.getStepZ() * up.getStepY(),
                facing.getStepZ() * up.getStepX() - facing.getStepX() * up.getStepZ(),
                facing.getStepX() * up.getStepY() - facing.getStepY() * up.getStepX());
    }

    private static double dot(Vec3 v, Direction d) { return v.x * d.getStepX() + v.y * d.getStepY() + v.z * d.getStepZ(); }

    public static Vec3 speaker(BlockPos root, BlockState state, int index) {
        var layout = KeyboardLayout.PX;
        return point(root, state, layout.speakers.get(index).add(0, state.getValue(STAND) ? 0 : layout.tabletop, 0));
    }

    public static Part dcInput(BlockState state) {
        if (!state.getValue(FULL_SIZE)) return Part.BASS;
        var at = modelToLocal(state, dcJack(state));
        return at.y >= 1 ? Part.TOP_BASS : Part.BASS;
    }

    public static Vec3 dcJack(BlockPos root, BlockState state) {
        return point(root, state, dcJack(state));
    }

    private static Vec3 dcJack(BlockState state) {
        var layout = KeyboardLayout.PX;
        return layout.dcIn.add(0, state.getValue(STAND) ? 0 : layout.tabletop, 0);
    }

    private static boolean isDcPort(BlockPos pos, BlockState state, Direction back) {
        return state.is(PxS1100Feature.BLOCK.get()) && state.getValue(FACING).getOpposite() == back
                && dcInput(state).at(root(pos, state), state).equals(pos);
    }

    public static boolean isAdapterPort(BlockPos pos, BlockState state, Direction back) {
        if (isDcPort(pos, state, back)) return true;
        // A stand also accepts a floor-mounted adapter, whose lead reaches the raised jack.
        return state.is(PxS1100Feature.BLOCK.get()) && state.getValue(STAND) && state.getValue(PART) == Part.BASS
                && state.getValue(FACING).getOpposite() == back;
    }

    static boolean powerInput(BlockState state, Part part) {
        // Existing floor-level wiring stays connected after a compact keyboard expands.
        return part == Part.BASS || part == dcInput(state);
    }

    private static void invalidatePowerInputs(Level level, BlockPos root, BlockState state) {
        for (var part : parts(state)) {
            if (powerInput(state, part)) level.invalidateCapabilities(part.at(root, state));
        }
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(FULL_SIZE, true);
        var root = context.getClickedPos();
        var level = context.getLevel();
        return supported(level, root, state) && canChange(level, root, null, state, context.getPlayer()) ? state : null;
    }

    private static boolean supported(Level level, BlockPos root, BlockState state) {
        return parts(state).stream().filter(part -> part.y == 0)
                .allMatch(part -> PianoPlacement.supported(level, part.at(root, state), up(state)));
    }

    private static boolean canChange(Level level, BlockPos root, @Nullable BlockState before, BlockState after, @Nullable Player player) {
        for (var part : parts(after)) {
            var pos = part.at(root, after);
            if (!PianoPlacement.fits(level, root, pos, shape(after.setValue(PART, part)), player)) return false;
            var current = level.getBlockState(pos);
            if (!(before != null && sameInstrument(pos, current, root, before))
                    && (!current.canBeReplaced() || !level.getFluidState(pos).isEmpty())) return false;
        }
        if (before != null) {
            for (var part : parts(before)) {
                var pos = part.at(root, before);
                if (!level.isLoaded(pos) || player != null && !SableCompat.mayInteract(level, player, pos)) return false;
            }
        }
        return true;
    }

    static boolean place(Level level, BlockPos root, BlockState state) {
        return change(level, root, null, state, null);
    }

    static boolean change(Level level, BlockPos root, @Nullable BlockState before, BlockState after, @Nullable Player player) {
        if (after.equals(before)) return true;
        if (!canChange(level, root, before, after, player)) return false;
        Map<BlockPos, BlockState> target = new LinkedHashMap<>();
        for (var part : parts(after)) target.put(part.at(root, after), after.setValue(PART, part));
        if (!PianoPlacement.install(level, target)) return false;
        if (before != null) {
            for (var part : parts(before)) {
                var pos = part.at(root, before);
                if (!target.containsKey(pos) && sameInstrument(pos, level.getBlockState(pos), root, before)) {
                    level.removeBlock(pos, true);
                }
            }
            invalidatePowerInputs(level, root, before);
        }
        invalidatePowerInputs(level, root, after);
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        var root = root(pos, state);
        if (player instanceof ServerPlayer server && level.getBlockEntity(root) instanceof KeyboardBlockEntity keyboard) {
            var runtime = keyboard.runtime();
            var local = local(root, state, SableCompat.hitInBlockSpace(level, pos, hit.getLocation()));
            interact(runtime, server, state, local);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!BatteryItem.fits(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player instanceof ServerPlayer server && level.getBlockEntity(root(pos, state)) instanceof KeyboardBlockEntity keyboard) {
            keyboard.runtime().battery(server, stack);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    static void interact(KeyboardRuntime runtime, ServerPlayer player, BlockState state, Vec3 local) {
        if (state.getValue(STAND) && KeyboardLayout.PEDAL_BOX.inflate(.014).contains(local)) {
            runtime.panel(player, "pedal");
            return;
        }
        var layout = KeyboardLayout.PX;
        var at = local.add(0, state.getValue(STAND) ? 0 : -layout.tabletop, 0);
        for (var button : layout.panel.entrySet()) {
            if (button.getValue().contains(at)) {
                runtime.panel(player, button.getKey());
                return;
            }
        }
        if (player.isShiftKeyDown()) {
            for (var key : layout.keys) {
                if (key.hits().stream().anyMatch(hit -> hit.contains(at))) {
                    runtime.tap(player, key.note());
                    return;
                }
            }
        }
        runtime.open(player, false);
    }

    public static boolean onKnob(Level level, BlockHitResult hit, Target target) {
        var state = level.getBlockState(hit.getBlockPos());
        if (!state.is(PxS1100Feature.BLOCK.get()) || !root(hit.getBlockPos(), state).equals(target.pos())) return false;
        var at = local(target.pos(), state, SableCompat.hitInBlockSpace(level, hit.getBlockPos(), hit.getLocation()));
        return KeyboardLayout.PX.panel.get("volume_knob").contains(at.add(0,
                state.getValue(STAND) ? 0 : -KeyboardLayout.PX.tabletop, 0));
    }

    static boolean looksAtKnob(ServerPlayer player, Target target) {
        return !target.mounted() && player.pick(5.5, 1, false) instanceof BlockHitResult hit
                && onKnob(player.level(), hit, target);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        var level = context.getLevel();
        var root = root(context.getClickedPos(), state);
        var main = level.getBlockState(root);
        if (!main.is(this)) return InteractionResult.PASS;
        var rotated = rotate(main, Rotation.CLOCKWISE_90);
        if (!supported(level, root, rotated)) return InteractionResult.FAIL;
        boolean rotatedOk = level.isClientSide ? canChange(level, root, main, rotated, context.getPlayer())
                : change(level, root, main, rotated, context.getPlayer());
        return rotatedOk ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.FAIL;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!context.getLevel().isClientSide) context.getLevel().destroyBlock(root(context.getClickedPos(), state), !player.isCreative(), player);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.MAIN) {
            var root = root(pos, state);
            if (part(level.getBlockState(root), Part.MAIN, state)) level.destroyBlock(root, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !moved && !next.is(this)) {
            if (state.getValue(PART) == Part.MAIN) {
                for (var part : parts(state)) {
                    if (part == Part.MAIN) continue;
                    var at = part.at(pos, state);
                    if (part(level.getBlockState(at), part, state)) level.removeBlock(at, true);
                }
            } else {
                var root = root(pos, state);
                if (part(level.getBlockState(root), Part.MAIN, state)) level.destroyBlock(root, true);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }

    static boolean part(BlockState candidate, Part part, BlockState reference) {
        return candidate.is(PxS1100Feature.BLOCK.get()) && candidate.getValue(PART) == part
                && candidate.getValue(FACING) == reference.getValue(FACING)
                && candidate.getValue(FULL_SIZE).equals(reference.getValue(FULL_SIZE))
                && up(candidate) == up(reference);
    }

    private static boolean sameInstrument(BlockPos pos, BlockState candidate, BlockPos root, BlockState reference) {
        return candidate.is(reference.getBlock()) && root(pos, candidate).equals(root)
                && part(candidate, candidate.getValue(PART), reference);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING))).setValue(UP, rotation.rotate(up(state)));
    }

    @Override public BlockState transform(BlockState state, StructureTransform transform) {
        return state.setValue(FACING, transform.rotateFacing(state.getValue(FACING)))
                .setValue(UP, transform.rotateFacing(up(state)));
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state);
    }

    private static VoxelShape shape(BlockState state) {
        return COLLISION.computeIfAbsent(state, block -> {
            var layout = KeyboardLayout.PX;
            var boxes = new ArrayList<AABB>();
            boolean onStand = block.getValue(STAND);
            boxes.add(layout.body.move(0, onStand ? 0 : layout.tabletop, 0));
            if (onStand) {
                boxes.addAll(layout.stand);
                boxes.add(KeyboardLayout.PEDAL_BOX);
            }
            if (block.getValue(FULL_SIZE) && block.getValue(RACK)) {
                boxes.add(layout.rack.move(0, onStand ? 0 : layout.tabletop, 0));
            }
            var part = block.getValue(PART);
            VoxelShape result = Shapes.empty();
            for (AABB box : boxes) {
                var min = modelToLocal(block, new Vec3(box.minX, box.minY, box.minZ));
                var max = modelToLocal(block, new Vec3(box.maxX, box.maxY, box.maxZ));
                var start = new Vec3(Math.max(0, min.x - part.x), Math.max(0, min.y - part.y), min.z);
                var end = new Vec3(Math.min(1, max.x - part.x), Math.min(1, max.y - part.y), max.z);
                if (start.x >= end.x || start.y >= end.y) continue;
                var a = orient(BlockPos.ZERO, block, start);
                var b = orient(BlockPos.ZERO, block, end);
                result = Shapes.or(result, Shapes.create(new AABB(a, b)));
            }
            return result;
        });
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.MAIN ? new KeyboardBlockEntity(pos, state) : null;
    }

    @Override public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(PART) != Part.MAIN) return null;
        return createTickerHelper(type, PxS1100Feature.ENTITY.get(), (world, pos, block, keyboard) -> {
            if (!block.getValue(FULL_SIZE) && world.getGameTime() % 20 == 0 && !SableCompat.inPlotGrid(world, pos)) {
                upgrade(world, pos, block);
            }
            keyboard.runtime().tick();
        });
    }

    private static void upgrade(Level level, BlockPos root, BlockState before) {
        var bass = Part.BASS.at(root, before);
        if (!level.isLoaded(bass) || !part(level.getBlockState(bass), Part.BASS, before)) return;
        var expanded = before.setValue(FULL_SIZE, true);
        if (!supported(level, root, expanded)) return;
        for (var part : parts(expanded)) {
            var at = part.at(root, expanded);
            if (!level.isLoaded(at)) return;
            var current = level.getBlockState(at);
            if (!current.isAir() && !sameInstrument(at, current, root, before)) return;
        }
        change(level, root, before, expanded, null);
    }
}
