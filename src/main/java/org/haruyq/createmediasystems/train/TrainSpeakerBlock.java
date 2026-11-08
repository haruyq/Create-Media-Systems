package org.haruyq.createmediasystems.train;

import com.mojang.serialization.MapCodec;

import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Plays the announcements of every {@link TrainDiscReaderBlock} on its train, at the volume set on the reader, to
 * those aboard its own carriage only. Needs no linking; it does nothing off a train.
 */
public class TrainSpeakerBlock extends DirectionalBlock implements IWrenchable {
    private static final MapCodec<TrainSpeakerBlock> CODEC = simpleCodec(TrainSpeakerBlock::new);

    public TrainSpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN));
    }

    @Override
    protected MapCodec<TrainSpeakerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }
}
