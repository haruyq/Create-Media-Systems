package org.haruyq.createmediasystems.items.instrument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A pedal with a three-position effect-strength knob. */
public final class AutoPedalBlock extends GuitarPedalBlock {
    private static final IntegerProperty DEPTH = IntegerProperty.create("depth", 1, 3);
    private static final VoxelShape NORTH_SOUTH = box(3.5, 0, 1.5, 12.5, 4, 14.5);
    private static final VoxelShape EAST_WEST = box(1.5, 0, 3.5, 14.5, 4, 12.5);
    AutoPedalBlock(AutoEffect effect, Properties properties) {
        super(effect, properties);
        registerDefaultState(defaultBlockState().setValue(DEPTH, 2));
    }

    @Override
    GuitarPatch.Setting setting(GuitarPatch.Link link, Level level, BlockState state) {
        return new GuitarPatch.Setting(link, amount(state));
    }

    /** 0 is bypass, 1–3 are increasing effect strengths. */
    public int amount(BlockState state) {
        return enabled(state) ? state.getValue(DEPTH) : 0;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(DEPTH);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? EAST_WEST : NORTH_SOUTH;
    }

    @Override
    protected void press(Level level, BlockPos pos, BlockState state, Player player) {
        if (!player.isShiftKeyDown()) {
            super.press(level, pos, state, player);
            return;
        }
        BlockState next = state.cycle(DEPTH);
        level.setBlockAndUpdate(pos, next);
        player.displayClientMessage(Component.translatable("guitar_patch.create_media_systems.depth",
                effect().displayName(), next.getValue(DEPTH)), true);
    }
}
