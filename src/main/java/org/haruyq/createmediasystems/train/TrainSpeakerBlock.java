package org.haruyq.createmediasystems.train;

import org.haruyq.createmediasystems.audio.SpeakerBlock;

import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Plays the announcements of every {@link TrainDiscReaderBlock} on its train, at the volume set on the reader, to
 * those aboard its own carriage only. Needs no linking; it does nothing off a train.
 */
public class TrainSpeakerBlock extends Block implements IWrenchable {
    public TrainSpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SpeakerBlock.FACING, Direction.DOWN));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SpeakerBlock.FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(SpeakerBlock.FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(SpeakerBlock.FACING, rotation.rotate(state.getValue(SpeakerBlock.FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(SpeakerBlock.FACING, mirror.mirror(state.getValue(SpeakerBlock.FACING)));
    }
}
