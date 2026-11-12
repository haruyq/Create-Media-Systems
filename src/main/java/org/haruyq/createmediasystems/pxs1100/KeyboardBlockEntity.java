package org.haruyq.createmediasystems.pxs1100;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.pxs1100.KeyboardPackets.Target;
import org.haruyq.createmediasystems.stream.LiveSource;
import org.haruyq.createmediasystems.stream.Streams;

public final class KeyboardBlockEntity extends BlockEntity implements KeyboardRuntime.Host, LiveSource {
    static final String DATA = "Keyboard";
    public static final double AUDIBLE_RANGE = KeyboardRuntime.RANGE;

    private CompoundTag data = new CompoundTag();
    private CompoundTag view = new CompoundTag();
    private Target target;
    private BlockState mountedState;
    private KeyboardRuntime runtime;

    public KeyboardBlockEntity(BlockPos pos, BlockState state) {
        super(PxS1100Feature.ENTITY.get(), pos, state);
        target = Target.placed(pos);
    }

    KeyboardRuntime runtime() {
        if (runtime == null) runtime = new KeyboardRuntime(this, data);
        return runtime;
    }

    public CompoundTag clientView() { return view; }
    @Override public Target target() { return target; }
    @Override public ServerLevel world() { return (ServerLevel) level; }
    @Override public BlockState state() { return mountedState == null ? getBlockState() : mountedState; }

    void showMounted(int carrier, BlockState state) {
        if (target.carrier() != carrier) target = new Target(carrier, worldPosition);
        mountedState = state;
    }

    @Override public KeyboardPackets.Frame frame() {
        return new KeyboardPackets.Frame(
                SableCompat.toWorld(level, worldPosition, KeyboardBlock.speaker(worldPosition, state(), 0)),
                SableCompat.toWorld(level, worldPosition, KeyboardBlock.speaker(worldPosition, state(), 1)));
    }

    @Override
    public void heardThrough(ServerLevel level, BlockPos emitter, Vec3 origin, Optional<Direction> facing,
            Streams.Channel channel, float volume) {
        var sound = new Streams.Sound(origin, facing, channel, worldPosition);
        runtime().heardThrough(new KeyboardPackets.Output(emitter.immutable(), sound, volume), level.getGameTime());
    }

    @Override public void decorations(boolean stand, boolean rack) {
        var state = getBlockState();
        var pos = KeyboardBlock.bass(worldPosition, state);
        var bass = level.getBlockState(pos);
        if (!KeyboardBlock.part(bass, KeyboardBlock.Part.BASS, state)) return;
        level.setBlock(worldPosition, decorated(state, stand, rack), Block.UPDATE_ALL);
        level.setBlock(pos, decorated(bass, stand, rack), Block.UPDATE_ALL);
    }

    static BlockState decorated(BlockState state, boolean stand, boolean rack) {
        return state.setValue(KeyboardBlock.STAND, stand).setValue(KeyboardBlock.RACK, rack);
    }

    @Override public void changed(boolean sync) {
        setChanged();
        if (sync) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(DATA, runtime == null ? data.copy() : runtime.save());
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("View")) {
            view = tag.getCompound("View");
        } else if (tag.contains(DATA)) {
            data = tag.getCompound(DATA);
            if (runtime != null) runtime.removed();
            runtime = null;
        }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.put("View", level instanceof ServerLevel ? runtime().view() : view.copy());
        return tag;
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override public void setRemoved() {
        if (runtime != null) runtime.removed();
        super.setRemoved();
    }
}
