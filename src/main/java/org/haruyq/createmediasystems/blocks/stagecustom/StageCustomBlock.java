package org.haruyq.createmediasystems.blocks.stagecustom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.PianoPlacement;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.jetbrains.annotations.Nullable;

public final class StageCustomBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    static final BooleanProperty LARGE = BooleanProperty.create("large");
    private static final float LARGE_SCALE = 1.5f;
    private static final Set<Part> ORIGINAL_PARTS = Set.of(Part.ROOT, Part.LEFT, Part.LEFT_REAR);
    private static final Map<Direction, Map<Part, VoxelShape>> ORIGINAL_SHAPES = createShapes(1);
    private static final Map<Direction, Map<Part, VoxelShape>> LARGE_SHAPES = createShapes(LARGE_SCALE);
    private static final Drum[] DRUMS = Drum.values();

    public enum Part implements StringRepresentable {
        ROOT(0, 0, 0), LEFT(-1, 0, 0), LEFT_REAR(-1, 0, 1),
        RIGHT(1, 0, 0), REAR(0, 0, 1), RIGHT_REAR(1, 0, 1),
        TOP(0, 1, 0), TOP_LEFT(-1, 1, 0), TOP_LEFT_REAR(-1, 1, 1),
        TOP_RIGHT(1, 1, 0), TOP_REAR(0, 1, 1), TOP_RIGHT_REAR(1, 1, 1);

        final int x, y, z;

        Part(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public BlockPos at(BlockPos root, Direction facing) {
            return root.relative(facing.getCounterClockWise(), x).relative(facing, z).above(y);
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    StageCustomBlock(Properties properties) {
        super(properties);
        // Saves from the first kit layout have no "large" property.
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH)
                .setValue(PART, Part.ROOT).setValue(LARGE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, LARGE);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(LARGE, true);
        return fits(context.getLevel(), context.getClickedPos(), state, context.getPlayer()) ? state : null;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    static boolean install(Level level, BlockPos root, BlockState state, @Nullable Player player) {
        Direction facing = state.getValue(FACING);
        if (!fits(level, root, state, player)) return false;
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (Part part : parts(state)) blocks.put(part.at(root, facing), state.setValue(PART, part));
        return PianoPlacement.install(level, blocks);
    }

    private static boolean fits(Level level, BlockPos root, BlockState state, @Nullable Player player) {
        for (var entry : shapes(state).entrySet()) {
            Part part = entry.getKey();
            BlockPos at = part.at(root, state.getValue(FACING));
            if (!PianoPlacement.fits(level, root, at, entry.getValue(), player)
                    || (part.y == 0 && !PianoPlacement.supported(level, at, Direction.UP))
                    || !level.getBlockState(at).canBeReplaced() || !level.getFluidState(at).isEmpty()) return false;
        }
        return true;
    }

    public static BlockPos root(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        var facing = state.getValue(FACING);
        return pos.relative(facing.getCounterClockWise(), -part.x).relative(facing, -part.z).below(part.y);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.ROOT ? new Entity(pos, state) : null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && state.getValue(PART).y == 0 && !neighbor.isFaceSturdy(level, neighborPos, Direction.UP)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes(state).getOrDefault(state.getValue(PART), Shapes.empty());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack item, BlockState state, Level level, BlockPos pos, Player player,
                                               InteractionHand hand, BlockHitResult hit) {
        if (item.isEmpty()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!item.is(Items.STICK)) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return strikeAt(state, level, player, hit).consumesAction()
                ? ItemInteractionResult.sidedSuccess(level.isClientSide) : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return strikeAt(state, level, player, hit);
    }

    private static InteractionResult strikeAt(BlockState state, Level level, Player player, BlockHitResult click) {
        BlockPos pos = click.getBlockPos();
        BlockPos root = root(pos, state);
        var facing = state.getValue(FACING);
        if (!(level.getBlockEntity(root) instanceof Entity entity) || !samePart(entity.getBlockState(), state, Part.ROOT)) {
            return InteractionResult.PASS;
        }
        Vec3 eye = SableCompat.eye(player, 1);
        Vec3 localEye = SableCompat.toBlockSpace(level, root, eye);
        Vec3 localHit = SableCompat.hitInBlockSpace(level, pos, click.getLocation());
        float scale = scale(state);
        Vec3 modelEye = StageCustomLayout.toModel(facing, localEye.subtract(Vec3.atBottomCenterOf(root))).scale(1d / scale);
        Vec3 modelDirection = StageCustomLayout.toModel(facing, localHit.subtract(localEye)).normalize();
        var hit = StageCustomLayout.aim(modelEye, modelDirection, (player.blockInteractionRange() + .08) / scale);
        if (hit == null) return InteractionResult.PASS;
        if (!level.isClientSide) entity.strike(hit.drum(), hit.u(), hit.v(), player.isShiftKeyDown() ? 59 : 110);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int value) {
        return level.getBlockEntity(pos) instanceof Entity entity && entity.triggerEvent(id, value);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.ROOT) {
            BlockPos root = root(pos, state);
            if (samePart(level.getBlockState(root), state, Part.ROOT)) level.destroyBlock(root, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!level.isClientSide && !moving && !next.is(this)) {
            var part = state.getValue(PART);
            BlockPos root = root(pos, state);
            if (part == Part.ROOT) {
                for (Part child : parts(state)) {
                    if (child == Part.ROOT) continue;
                    BlockPos other = child.at(root, state.getValue(FACING));
                    if (samePart(level.getBlockState(other), state, child)) level.removeBlock(other, false);
                }
            } else if (samePart(level.getBlockState(root), state, Part.ROOT)) {
                level.destroyBlock(root, true);
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }

    private static boolean samePart(BlockState actual, BlockState source, Part part) {
        return actual.is(source.getBlock()) && actual.getValue(PART) == part
                && actual.getValue(FACING) == source.getValue(FACING) && actual.getValue(LARGE) == source.getValue(LARGE);
    }

    public static float scale(BlockState state) {
        return state.getValue(LARGE) ? LARGE_SCALE : 1;
    }

    static Set<Part> parts(BlockState state) {
        return shapes(state).keySet();
    }

    private static Map<Part, VoxelShape> shapes(BlockState state) {
        return (state.getValue(LARGE) ? LARGE_SHAPES : ORIGINAL_SHAPES).get(state.getValue(FACING));
    }

    private static Map<Direction, Map<Part, VoxelShape>> createShapes(float scale) {
        var shapes = new EnumMap<Direction, Map<Part, VoxelShape>>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var parts = new EnumMap<Part, VoxelShape>(Part.class);
            for (Part part : Part.values()) {
                if (scale == 1 && !ORIGINAL_PARTS.contains(part)) continue;
                BlockPos offset = part.at(BlockPos.ZERO, facing);
                VoxelShape shape = Shapes.empty();
                for (AABB bounds : StageCustomLayout.bounds()) {
                    Vec3 a = StageCustomLayout.toWorld(BlockPos.ZERO, facing, new Vec3(bounds.minX, bounds.minY, bounds.minZ).scale(scale));
                    Vec3 b = StageCustomLayout.toWorld(BlockPos.ZERO, facing, new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ).scale(scale));
                    var cell = new AABB(a, b).move(-offset.getX(), -offset.getY(), -offset.getZ());
                    if (!cell.intersects(0, 0, 0, 1, 1, 1)) continue;
                    shape = Shapes.or(shape, Shapes.box(Math.max(cell.minX, 0), Math.max(cell.minY, 0),
                            Math.max(cell.minZ, 0), Math.min(cell.maxX, 1), Math.min(cell.maxY, 1), Math.min(cell.maxZ, 1)));
                }
                if (!shape.isEmpty()) parts.put(part, shape.optimize());
            }
            shapes.put(facing, parts);
        }
        return shapes;
    }

    public static final class Entity extends BlockEntity {
        public record Strike(Drum drum, float u, float v, float strength, int hand, long time) {}
        private final ArrayDeque<Strike> strikes = new ArrayDeque<>();
        private int nextHand;

        Entity(BlockPos pos, BlockState state) {
            super(StageCustomFeature.ENTITY.get(), pos, state);
        }

        void strike(Drum drum, float u, float v, int velocity) {
            if (level == null || level.isClientSide) return;
            int hand = nextHand++ & 1;
            int strength = Math.max(1, Math.round(velocity * (15 / 127f)));
            // Vanilla carries both arguments as unsigned bytes.
            int id = drum.ordinal() | hand << 3 | strength << 4;
            int value = encode(u) | encode(v) << 4;
            level.blockEvent(worldPosition, getBlockState().getBlock(), id, value);
            var shell = StageCustomLayout.shell(drum);
            Vec3 contact = shell.point(u * shell.headRadius(), v * shell.headRadius(), 0);
            Vec3 local = StageCustomLayout.toWorld(worldPosition, getBlockState().getValue(FACING), contact.scale(scale(getBlockState())));
            Vec3 at = SableCompat.toWorld(level, worldPosition, local);
            level.playSound(null, at.x, at.y, at.z, StageCustomFeature.sound(drum), SoundSource.RECORDS,
                    .95f * velocity / 127f, .99f + .018f * level.random.nextFloat());
        }

        private static int encode(float position) {
            return Math.round(Math.clamp(position, -1, 1) * 7) + 7;
        }

        @Override
        public boolean triggerEvent(int id, int value) {
            int drum = id & 7, velocity = id >>> 4;
            int u = value & 15, v = value >>> 4;
            if (drum >= DRUMS.length || velocity == 0 || velocity > 15 || u > 14 || v > 14) {
                return super.triggerEvent(id, value);
            }
            if (level != null && level.isClientSide) {
                if (strikes.size() >= 32) strikes.removeFirst();
                strikes.addLast(new Strike(DRUMS[drum], (u - 7) / 7f, (v - 7) / 7f, velocity / 15f,
                        id >>> 3 & 1, System.nanoTime()));
            }
            return true;
        }

        public List<Strike> drainStrikes() {
            if (strikes.isEmpty()) return List.of();
            var updates = new ArrayList<>(strikes);
            strikes.clear();
            return updates;
        }
    }
}
