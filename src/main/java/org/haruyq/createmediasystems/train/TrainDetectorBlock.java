package org.haruyq.createmediasystems.train;

import java.util.List;
import java.util.UUID;


import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.observer.TrackObserver;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Train detector: placed onto a track like Create's Train Observer (right-click the track first). While a train is
 * over it, its channel is on for every {@link TrainSignalReceiverBlock} with that channel, at any distance and even
 * with this block's chunk unloaded (see {@link TrainSignals}).
 */
public class TrainDetectorBlock extends Block implements IBE<TrainDetectorBlock.Entity>, IWrenchable {
    public TrainDetectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide && level.getBlockEntity(pos) instanceof Entity detector) {
            TrainSignals.promptChannel(pos, detector.channel);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof Entity detector && detector.registered != null) {
            TrainSignals.get(server.getServer()).remove(detector.registered);
        }
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<Entity> getBlockEntityClass() {
        return Entity.class;
    }

    @Override
    public BlockEntityType<? extends Entity> getBlockEntityType() {
        return TrainFeature.TRAIN_DETECTOR_ENTITY.get();
    }

    public static class Entity extends SmartBlockEntity implements ChannelHolder {
        public TrackTargetingBehaviour<TrackObserver> edgePoint;
        private String channel = "";
        /** The observer whose channel is in {@link TrainSignals}; re-registered if Create replaces it. */
        @Nullable
        private UUID registered;

        public Entity(BlockPos pos, BlockState state) {
            super(TrainFeature.TRAIN_DETECTOR_ENTITY.get(), pos, state);
        }

        @Override
        public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(edgePoint = new TrackTargetingBehaviour<>(this, EdgePointType.OBSERVER));
        }

        @Override
        public void setChannel(String channel) {
            this.channel = TrainSignals.normalize(channel);
            setChanged();
            sendData();
            register();
        }

        @Override
        public void tick() {
            super.tick();
            TrackObserver observer = edgePoint.getEdgePoint();
            if (observer != null && !observer.getId().equals(registered)) {
                register();
            }
        }

        private void register() {
            TrackObserver observer = edgePoint.getEdgePoint();
            if (level instanceof ServerLevel server && observer != null) {
                TrainSignals.get(server.getServer()).setChannel(observer.getId(), channel);
                registered = observer.getId();
            }
        }

        @Override
        protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            tag.putString("Channel", channel);
            super.write(tag, registries, clientPacket);
        }

        @Override
        protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            channel = TrainSignals.normalize(tag.getString("Channel"));
            super.read(tag, registries, clientPacket);
        }

        @Override
        protected AABB createRenderBoundingBox() {
            return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(edgePoint.getGlobalPosition())).inflate(2);
        }
    }
}
