package org.haruyq.createmediasystems.blocks.steelpan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanPackets.Target;

public final class SteelpanBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE = Shapes.or(box(2, 11, 2, 14, 15.9, 14),
            box(2.4, 0, 2.8, 3.6, 15.3, 13.2), box(12.4, 0, 2.8, 13.6, 15.3, 13.2));
    private static final VoxelShape SIDE_SHAPE = Shapes.or(box(2, 11, 2, 14, 15.9, 14),
            box(2.8, 0, 2.4, 13.2, 15.3, 3.6), box(2.8, 0, 12.4, 13.2, 15.3, 13.6));

    public SteelpanBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SIDE_SHAPE : SHAPE;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Entity(pos, state); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof Entity pan) || player.isSpectator()) return InteractionResult.PASS;
        var target = Target.placed(pos);
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer server) SteelpanPackets.open(server, target);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        Vec3 eye = SableCompat.eye(player, 1);
        Vec3 localEye = SableCompat.toBlockSpace(level, pos, eye);
        Vec3 hitInPlot = SableCompat.hitInBlockSpace(level, pos, hit.getLocation());
        Vec3 direction = hitInPlot.subtract(localEye).normalize();
        int midi = rayNote(localEye.subtract(Vec3.atBottomCenterOf(pos)), direction, state.getValue(FACING));
        if (midi < 0) return InteractionResult.PASS;
        if (SteelpanPackets.canPlay(player, target)) {
            if (level.isClientSide) SteelpanPackets.played(target, midi, 100);
            else pan.strike(midi, 100);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Intersect the real tilted bowl rather than selecting a pitch on an invisible rectangular lid. */
    static int rayNote(Vec3 eye, Vec3 direction, Direction facing) {
        double yaw = Math.toRadians(facing.toYRot());
        Vec3 origin = eye.yRot((float) yaw).subtract(0, SteelpanLayout.DATA.rim_height(), 0)
                .xRot((float) Math.toRadians(SteelpanLayout.DATA.tilt()));
        Vec3 ray = direction.yRot((float) yaw).xRot((float) Math.toRadians(SteelpanLayout.DATA.tilt()));
        // Vec3.xRot uses the inverse sign to PoseStack's right-handed X rotation.
        double radius = SteelpanLayout.DATA.diameter() / 2 - .004, k = .2107 / (radius * radius);
        double a = k * (ray.x * ray.x + ray.z * ray.z);
        double b = 2 * k * (origin.x * ray.x + origin.z * ray.z) - ray.y;
        double c = k * (origin.x * origin.x + origin.z * origin.z) - .2107 - origin.y;
        double discriminant = b * b - 4 * a * c;
        double[] roots = Math.abs(a) < 1e-9 ? new double[]{-c / b}
                : discriminant < 0 ? new double[0] : new double[]{(-b - Math.sqrt(discriminant)) / (2*a), (-b + Math.sqrt(discriminant)) / (2*a)};
        for (double t : roots) {
            if (!Double.isFinite(t) || t < 0 || t > 6 || ray.y >= 0) continue;
            double x = origin.x + ray.x * t, z = origin.z + ray.z * t;
            if (x*x+z*z <= radius*radius) return SteelpanLayout.at((float) x, (float) z);
        }
        return -1;
    }

    public static final class Entity extends BlockEntity {
        private boolean left;

        public Entity(BlockPos pos, BlockState state) {
            super(SteelpanFeature.ENTITY.get(), pos, state);
        }

        public void strike(int midi, int velocity) {
            strike(midi, velocity, left ? 1 : 0);
        }

        /** Plays the field with an explicit mallet: 0 for left, 1 for right. Call on the server thread. */
        public void strike(int midi, int velocity, int hand) {
            if (!(level instanceof ServerLevel server) || !SteelpanPackets.valid(midi, velocity) || hand < 0 || hand > 1) return;
            left = hand == 0;
            SteelpanPackets.broadcast(server, new SteelpanPackets.Strike(Target.placed(worldPosition), midi, velocity, left));
        }
    }
}
