package org.haruyq.createmediasystems.blocks.soundproof;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A soundproof door: a vanilla door in what it is to the world (two blocks high, opened by hand or redstone), whose
 * leaf the client draws moving (see SoundproofDoorRenderer) inside the frame that is its block model. Its lower
 * half has the block entity that keeps how far open the leaf is drawn.
 */
public final class SoundproofDoorBlock extends DoorBlock implements EntityBlock {
    public enum Kind {
        /** A steel leaf with a narrow window by its lever. */
        NORMAL,
        /** A frame around one large pane of frosted glass. */
        GLASS,
        /**
         * Hangs from a rail on the wall in front of its opening and slides along it, to its hinge side, before the
         * block beside it: it needs the space before that block free.
         */
        SLIDING,
        /** One leaf of two that open together; its item puts both, side by side (see SoundproofFeature). */
        DOUBLE
    }

    /** Steel, yet opened by hand as a studio's door is. */
    private static final BlockSetType TYPE = new BlockSetType("create_media_systems_soundproof", true, false, false,
            BlockSetType.PressurePlateSensitivity.MOBS, SoundType.METAL, SoundEvents.IRON_DOOR_CLOSE,
            SoundEvents.IRON_DOOR_OPEN, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundEvents.IRON_TRAPDOOR_OPEN,
            SoundEvents.METAL_PRESSURE_PLATE_CLICK_OFF, SoundEvents.METAL_PRESSURE_PLATE_CLICK_ON,
            SoundEvents.STONE_BUTTON_CLICK_OFF, SoundEvents.STONE_BUTTON_CLICK_ON);

    private final Kind kind;
    /** Of each state, what of it is pointed at (its handle too) and what is in the way. */
    private final Map<BlockState, VoxelShape[]> shapes = new HashMap<>();

    public SoundproofDoorBlock(Kind kind, Properties properties) {
        super(TYPE, properties);
        this.kind = kind;
        for (BlockState state : getStateDefinition().getPossibleStates()) {
            shapes.put(state, shapes(state));
        }
    }

    public Kind kind() {
        return kind;
    }

    /** Where a sliding door's leaf goes as it opens, and a swinging one's hinge is, seen from its block. */
    public static Direction hingeSide(BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(HINGE) == DoorHingeSide.LEFT ? facing.getCounterClockWise() : facing.getClockWise();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state)[0];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state)[1];
    }

    /**
     * Boxes as the models are (sixteenths, of a door facing east, hinged left and closed, over both halves): the
     * leaf, as a vanilla door's, is what is in the way; pointed at is its handle as well, standing out of it. A
     * sliding leaf hangs before its block: open, what is in the way is its jamb, and pointed at is that with the
     * end of the leaf and its handle before it.
     */
    private VoxelShape[] shapes(BlockState state) {
        boolean open = state.getValue(OPEN);
        boolean sliding = kind == Kind.SLIDING;
        VoxelShape solid = placed(state, new double[] {0, 0, 0, 3, 32, sliding && open ? 3 : 16}, open && !sliding);
        double[] grip = switch (kind) {
            case SLIDING -> new double[] {-4, 0, 0, 3, 32, open ? 4 : 16};
            case DOUBLE -> state.getValue(HINGE) == DoorHingeSide.RIGHT ? new double[] {-1, 9, 10, 4, 19, 16} : null;
            default -> new double[] {-1, 11, 9, 4, 19, 15};
        };
        return new VoxelShape[] {grip == null ? solid : Shapes.or(solid, placed(state, grip, open && !sliding)), solid};
    }

    private static VoxelShape placed(BlockState state, double[] box, boolean swung) {
        boolean right = state.getValue(HINGE) == DoorHingeSide.RIGHT;
        double[] a = {box[0], right ? 16 - box[5] : box[2]};
        double[] b = {box[3], right ? 16 - box[2] : box[5]};
        int turns = switch (state.getValue(FACING)) {
            case NORTH -> 1;
            case WEST -> 2;
            case SOUTH -> 3;
            default -> 0;
        };
        for (double[] corner : new double[][] {a, b}) {
            if (swung) turn(corner, 1.5, right ? 14.5 : 1.5, right ? 3 : 1);
            turn(corner, 8, 8, turns);
        }
        double lift = state.getValue(HALF) == DoubleBlockHalf.UPPER ? 16 : 0;
        double y0 = Math.max(0, box[1] - lift);
        double y1 = Math.min(16, box[4] - lift);
        return y0 >= y1 ? Shapes.empty() : box(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]),
                Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
    }

    /** Quarter turns of a point (x, z) about another, the way the renderer turns a leaf. */
    private static void turn(double[] point, double x, double z, int quarters) {
        for (int i = 0; i < quarters; i++) {
            double dx = point[0] - x;
            point[0] = x + point[1] - z;
            point[1] = z - dx;
        }
    }

    // --- the two leaves of a double door --------------------------------------------------------------------------

    private static BlockPos partner(BlockState state, BlockPos pos) {
        return pos.relative(hingeSide(state).getOpposite());
    }

    private boolean isPartner(BlockState state, BlockState other) {
        return other.is(this) && other.getValue(FACING) == state.getValue(FACING)
                && other.getValue(HALF) == state.getValue(HALF) && other.getValue(HINGE) != state.getValue(HINGE);
    }

    /** One leaf opened or closed, by whatever did: the other follows. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (kind == Kind.DOUBLE && old.is(this) && old.getValue(OPEN) != state.getValue(OPEN)) {
            BlockPos beside = partner(state, pos);
            BlockState other = level.getBlockState(beside);
            if (isPartner(state, other) && other.getValue(OPEN) != state.getValue(OPEN)) {
                level.setBlock(beside, other.setValue(OPEN, state.getValue(OPEN)), UPDATE_CLIENTS | UPDATE_IMMEDIATE);
            }
        }
    }

    /** A leaf goes with the other one. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (kind == Kind.DOUBLE && neighborPos.equals(partner(state, pos)) && !isPartner(state, neighbor)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    /** Puts both leaves of a double door, the other to the right of the one placed if there is room, else left. */
    static final class DoubleItem extends DoubleHighBlockItem {
        DoubleItem(SoundproofDoorBlock block, Item.Properties properties) {
            super(block, properties);
        }

        @Override
        protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                BlockState mine = state.setValue(HINGE, hinge);
                BlockPos beside = partner(mine, pos);
                if (!level.getBlockState(beside).canBeReplaced() || !level.getBlockState(beside.above()).canBeReplaced()
                        || !level.getBlockState(beside.below()).isFaceSturdy(level, beside.below(), Direction.UP)
                        || !super.placeBlock(context, mine)) {
                    continue;
                }
                BlockState theirs = mine.setValue(HINGE, hinge == DoorHingeSide.LEFT ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT);
                level.setBlock(beside, theirs, UPDATE_ALL);
                level.setBlock(beside.above(), theirs.setValue(HALF, DoubleBlockHalf.UPPER), UPDATE_ALL);
                return true;
            }
            return false;
        }
    }

    // --- how far open its leaf is drawn ----------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new Entity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide && type == SoundproofFeature.DOOR_ENTITY.get()
                ? (l, pos, s, entity) -> ((Entity) entity).tick() : null;
    }

    public static final class Entity extends BlockEntity {
        /** Ticks a leaf takes from closed to open. */
        private static final float TICKS = 12;
        private float open;
        private float openBefore;

        public Entity(BlockPos pos, BlockState state) {
            super(SoundproofFeature.DOOR_ENTITY.get(), pos, state);
            open = openBefore = state.getValue(OPEN) ? 1 : 0;
        }

        private void tick() {
            openBefore = open;
            open = Mth.approach(open, getBlockState().getValue(OPEN) ? 1 : 0, 1 / TICKS);
        }

        /** How far open its leaf is at this part of a tick: 0 closed, 1 open. */
        public float open(float partialTick) {
            return Mth.lerp(partialTick, openBefore, open);
        }
    }
}
