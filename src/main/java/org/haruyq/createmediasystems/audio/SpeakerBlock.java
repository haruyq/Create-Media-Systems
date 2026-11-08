package org.haruyq.createmediasystems.audio;

import java.util.Optional;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.SourceLinks;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;

/**
 * Plays the track of a linked jukebox, disc reader or Blu-ray player, in sync with it, loudest in front. Its range is the
 * default radius; only its volume is set (with the range quill). Rotate it with a Create wrench.
 */
public class SpeakerBlock extends DirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    /** Where the sound comes from: the middle of the front face. */
    public static Vec3 mouth(BlockPos pos, Direction facing) {
        return pos.getCenter().add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.5));
    }

    @Override
    protected MapCodec<SpeakerBlock> codec() {
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

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != CreateMediaSystems.SPEAKER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l, s);
    }

    public static class Entity extends BlockEntity {
        private final SourceLinks.Link source = new SourceLinks.Link();
        private float volume = 1;
        private Streams.Channel channel = Streams.Channel.MIX;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateMediaSystems.SPEAKER_ENTITY.get(), pos, state);
        }

        public Optional<BlockPos> source() {
            return source.get(level, this::setChanged);
        }

        public float volume() {
            return volume;
        }

        public Streams.Channel channel() {
            return channel;
        }

        /** New volume: listeners keep playing at the new volume. New source or channel: they start over. */
        public void configure(ServerLevel level, @Nullable BlockPos source, float volume, Streams.Channel channel) {
            this.source.set(level, source);
            this.volume = Mth.clamp(volume, 0, 1);
            this.channel = channel;
            setChanged();
            relay(level, getBlockState());
        }

        private void tick(ServerLevel level, BlockState state) {
            if (level.getGameTime() % Streams.RELAY_TICKS == 0) {
                relay(level, state);
            }
        }

        private void relay(ServerLevel level, BlockState state) {
            Direction facing = state.getValue(FACING);
            Streams.relay(level, this, source(), mouth(worldPosition, facing), Optional.of(facing), channel, volume);
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && !level.isClientSide) {
                Streams.stop(this);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            source.save(level, tag);
            tag.putFloat("Volume", volume);
            tag.putInt("Channel", channel.ordinal());
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            source.load(tag);
            volume = tag.contains("Volume") ? tag.getFloat("Volume") : 1;
            channel = Streams.Channel.byId(tag.getInt("Channel"));
        }
    }
}
