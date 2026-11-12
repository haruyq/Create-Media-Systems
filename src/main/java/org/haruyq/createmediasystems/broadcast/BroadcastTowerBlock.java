package org.haruyq.createmediasystems.broadcast;

import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.haruyq.createmediasystems.audio.SpeakerBlock;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.Streams;

public final class BroadcastTowerBlock extends SpeakerBlock {
    private final BroadcastProfile profile;
    private final MapCodec<BroadcastTowerBlock> codec;
    private static final VoxelShape HEAD = box(1, 0, 1, 15, 16, 15);

    public BroadcastTowerBlock(Properties properties, BroadcastProfile profile) {
        super(properties);
        this.profile = profile;
        codec = simpleCodec(p -> new BroadcastTowerBlock(p, profile));
    }

    public BroadcastProfile profile() {
        return profile;
    }

    @Override
    protected MapCodec<BroadcastTowerBlock> codec() {
        return codec;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return HEAD;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Tower(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != BroadcastFeature.TOWER_ENTITY.get() ? null
                : (l, p, s, be) -> ((Tower) be).tick((ServerLevel) l);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server) {
            BroadcastCoverage.remove(server, pos);
        }
        super.onRemove(state, level, pos, next, moving);
    }

    public static final class Tower extends SpeakerBlock.Entity {
        private boolean broadcasting;
        private final MovingBroadcast onShip = new MovingBroadcast();
        private BlockPos registeredSource;

        public Tower(BlockPos pos, BlockState state) {
            super(BroadcastFeature.TOWER_ENTITY.get(), pos, state);
        }

        public BroadcastProfile profile() {
            return ((BroadcastTowerBlock) getBlockState().getBlock()).profile();
        }

        public Optional<BroadcastBaseBlock.Base> base(ServerLevel level) {
            BlockPos.MutableBlockPos below = worldPosition.mutable().move(0, -1, 0);
            while (!level.isOutsideBuildHeight(below) && level.getBlockState(below).is(BroadcastFeature.MAST.get())) {
                below.move(0, -1, 0);
            }
            return level.getBlockEntity(below) instanceof BroadcastBaseBlock.Base base ? Optional.of(base) : Optional.empty();
        }

        @Override
        public void tick(ServerLevel level) {
            update(level, Streams.relayDue(level));
        }

        @Override
        public void relay(ServerLevel level) {
            update(level, true);
        }

        private void update(ServerLevel level, boolean refresh) {
            followSource(level);
            BlockPos source = source().orElse(null);
            if (!Objects.equals(registeredSource, source)) {
                if (source == null) BroadcastCoverage.remove(level, worldPosition);
                else BroadcastCoverage.watch(level, worldPosition, source, profile());
                registeredSource = source;
            }
            boolean powered = source != null && volume() > 0 && Streams.source(level, source) != null
                    && base(level).filter(base -> base.supply(profile(), level.getGameTime())).isPresent();
            if (powered && SableCompat.containing(level, worldPosition) != null) {
                if (broadcasting) Streams.stop(this);
                broadcasting = false;
                float gain = level.getBlockEntity(source) instanceof MediaSource media ? media.gain() : 1;
                var frame = MovingBroadcastPackets.Frame.at(-1, worldPosition,
                        local -> SableCompat.toWorld(level, worldPosition, local));
                onShip.relay(level, frame, source, null, channel(), volume() * gain, profile(), refresh);
                return;
            }
            onShip.stop();
            if (powered && (refresh || !broadcasting)) {
                Streams.relay(level, this, Optional.of(source), worldPosition.getCenter().add(0, .26, 0),
                        Optional.empty(), channel(), volume(), profile());
            } else if (!powered && broadcasting) {
                Streams.stop(this);
            }
            broadcasting = powered;
        }

        @Override
        public void setRemoved() {
            onShip.stop();
            super.setRemoved();
        }
    }
}
