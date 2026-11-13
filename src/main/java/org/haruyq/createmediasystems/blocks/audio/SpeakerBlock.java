package org.haruyq.createmediasystems.blocks.audio;

import java.util.Optional;

import org.haruyq.createmediasystems.stream.Emitter;
import org.haruyq.createmediasystems.stream.Link;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;

public class SpeakerBlock extends DirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    public static Vec3 mouth(BlockPos pos, Direction facing) {
        return pos.getCenter().add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.5));
    }

    @Override
    protected MapCodec<? extends SpeakerBlock> codec() {
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
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != AudioFeature.SPEAKER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick((ServerLevel) l);
    }

    public static class Entity extends BlockEntity implements Emitter {
        private Link source = Link.NONE;
        private float volume = 1;
        private Streams.Channel channel = Streams.Channel.MIX;

        public Entity(BlockPos pos, BlockState state) {
            this(AudioFeature.SPEAKER_ENTITY.get(), pos, state);
        }

        protected Entity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        public Optional<BlockPos> source() {
            return Optional.ofNullable(source.pos());
        }

        public float volume() {
            return volume;
        }

        public Streams.Channel channel() {
            return channel;
        }

        /** New volume: listeners keep playing at the new volume. New source or channel: they start over. */
        public void configure(ServerLevel level, @Nullable BlockPos source, float volume, Streams.Channel channel) {
            this.source = Link.to(level, source);
            this.volume = Mth.clamp(volume, 0, 1);
            this.channel = channel;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            relay(level);
        }

        public void tick(ServerLevel level) {
            if (Streams.relayDue(level)) {
                relay(level);
            }
        }

        /** Its source as it is now, where Sable moved it. */
        public void followSource(ServerLevel level) {
            Link followed = source.follow(level);
            if (!followed.equals(source)) {
                source = followed;
                setChanged();
            }
        }

        @Override
        public void relay(ServerLevel level) {
            followSource(level);
            Direction facing = getBlockState().getValue(FACING);
            Streams.relay(level, this, source(), mouth(worldPosition, facing), Optional.of(facing), channel, volume);
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && !level.isClientSide) {
                Streams.stop(this);
            }
        }

        /** Clients know what it is linked to: a hi-fi speaker's cable is drawn to its amplifier. */
        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            source.save(tag, "Source", "SourceId");
            tag.putFloat("Volume", volume);
            tag.putInt("Channel", channel.ordinal());
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            source = Link.load(tag, "Source", "SourceId");
            volume = tag.contains("Volume") ? tag.getFloat("Volume") : 1;
            channel = Streams.Channel.byId(tag.getInt("Channel"));
        }
    }
}
