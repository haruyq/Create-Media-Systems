package org.haruyq.createmediasystems.keyboard;

import java.util.HashMap;
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
import org.haruyq.createmediasystems.keyboard.KeyboardPackets.Target;
import org.haruyq.createmediasystems.power.BatteryItem;
import org.jetbrains.annotations.Nullable;

public final class KeyboardBlock extends BaseEntityBlock implements IWrenchable, TransformableBlock, SableCompat.SilentAssemblyRemoval {
    public enum Part implements StringRepresentable {
        MAIN, BASS;
        @Override public String getSerializedName() { return this == MAIN ? "main" : "bass"; }
    }

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final DirectionProperty UP = DirectionProperty.create("up");
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty STAND = BooleanProperty.create("stand"), RACK = BooleanProperty.create("rack");
    private static final MapCodec<KeyboardBlock> CODEC = simpleCodec(KeyboardBlock::new);
    private static final Map<BlockState, VoxelShape> COLLISION = new java.util.concurrent.ConcurrentHashMap<>();

    KeyboardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(UP, Direction.UP)
                .setValue(PART, Part.MAIN).setValue(STAND, true).setValue(RACK, true));
    }

    @Override protected MapCodec<KeyboardBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, PART, STAND, RACK);
    }

    public static BlockPos bass(BlockPos root, BlockState state) { return root.relative(right(state)); }

    public static BlockPos root(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.MAIN ? pos : pos.relative(right(state).getOpposite());
    }

    public static Vec3 point(BlockPos root, BlockState state, Vec3 local) {
        return root.getCenter().relative(right(state), local.x - .5)
                .relative(up(state), local.y - .5)
                .relative(state.getValue(FACING).getOpposite(), local.z - .5);
    }

    static Vec3 local(BlockPos root, BlockState state, Vec3 point) {
        var d = point.subtract(root.getCenter());
        return new Vec3(.5 + dot(d, right(state)), .5 + dot(d, up(state)),
                .5 + dot(d, state.getValue(FACING).getOpposite()));
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

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        var root = context.getClickedPos();
        var level = context.getLevel();
        if (!canPlace(level, root, root, state, context.getPlayer())
                || !canPlace(level, root, bass(root, state), state.setValue(PART, Part.BASS), context.getPlayer())) return null;
        return state;
    }

    private static boolean canPlace(Level level, BlockPos root, BlockPos pos, BlockState state, @Nullable Player player) {
        var at = BlockPos.containing(SableCompat.toWorld(level, pos, pos.getCenter()));
        return SableCompat.sameSubLevel(level, root, pos) && level.isInWorldBounds(at) && level.getWorldBorder().isWithinBounds(at)
                && (player == null || SableCompat.mayInteract(level, player, pos))
                && level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && level.isUnobstructed(null, shape(state).move(pos.getX(), pos.getY(), pos.getZ()));
    }

    static boolean place(Level level, BlockPos root, BlockState state) {
        var bass = bass(root, state);
        var before = level.getBlockState(root);
        if (!before.canBeReplaced() || !level.getBlockState(bass).canBeReplaced() || !level.setBlock(root, state, Block.UPDATE_ALL)) return false;
        if (level.setBlock(bass, state.setValue(PART, Part.BASS), Block.UPDATE_ALL)) return true;
        level.setBlock(root, before, Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
        return false;
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
        if (!state.is(KeyboardFeature.BLOCK.get()) || !root(hit.getBlockPos(), state).equals(target.pos())) return false;
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
        var next = bass(root, rotated);
        if (!canPlace(level, root, next, rotated.setValue(PART, Part.BASS), context.getPlayer())) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            var previous = level.getBlockState(next);
            if (!level.setBlock(next, rotated.setValue(PART, Part.BASS), Block.UPDATE_ALL)) return InteractionResult.FAIL;
            if (!level.setBlock(root, rotated, Block.UPDATE_ALL)) {
                level.setBlock(next, previous, Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
                return InteractionResult.FAIL;
            }
            level.removeBlock(bass(root, main), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
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
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) == Part.BASS) {
            var root = root(pos, state);
            if (part(level.getBlockState(root), Part.MAIN, state)) level.destroyBlock(root, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !moved && !next.is(this)) {
            if (state.getValue(PART) == Part.MAIN) {
                var bass = bass(pos, state);
                if (part(level.getBlockState(bass), Part.BASS, state)) level.removeBlock(bass, true);
            } else {
                var root = root(pos, state);
                if (part(level.getBlockState(root), Part.MAIN, state)) level.destroyBlock(root, true);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }

    static boolean part(BlockState candidate, Part part, BlockState reference) {
        return candidate.is(KeyboardFeature.BLOCK.get()) && candidate.getValue(PART) == part
                && candidate.getValue(FACING) == reference.getValue(FACING)
                && up(candidate) == up(reference);
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
            var boxes = new java.util.ArrayList<AABB>();
            boolean onStand = block.getValue(STAND);
            boxes.add(layout.body.move(0, onStand ? 0 : layout.tabletop, 0));
            if (onStand) {
                boxes.addAll(layout.stand);
                boxes.add(KeyboardLayout.PEDAL_BOX);
            }
            int offset = block.getValue(PART) == Part.MAIN ? 0 : 1;
            VoxelShape result = Shapes.empty();
            for (AABB box : boxes) {
                double min = Math.max(offset, box.minX), max = Math.min(offset + 1, box.maxX);
                if (min >= max) continue;
                var a = point(BlockPos.ZERO, block, new Vec3(min - offset, box.minY, box.minZ));
                var b = point(BlockPos.ZERO, block, new Vec3(max - offset, box.maxY, box.maxZ));
                result = Shapes.or(result, Shapes.create(new AABB(a, b)));
            }
            return result;
        });
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.MAIN ? new KeyboardBlockEntity(pos, state) : null;
    }

    @Override public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(PART) == Part.BASS) return null;
        return createTickerHelper(type, KeyboardFeature.ENTITY.get(), (world, pos, block, keyboard) -> keyboard.runtime().tick());
    }
}
