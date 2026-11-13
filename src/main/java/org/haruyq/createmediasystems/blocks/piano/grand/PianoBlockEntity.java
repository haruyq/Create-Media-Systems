package org.haruyq.createmediasystems.blocks.piano.grand;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;
import org.jetbrains.annotations.Nullable;

public final class PianoBlockEntity extends BlockEntity {
    public static final int AUDIBLE_RANGE = PianoSession.AUDIBLE_RANGE;

    private PianoPackets.Target target;
    @Nullable private BlockState mountedState;
    @Nullable private PianoSession session;

    public PianoBlockEntity(BlockPos pos, BlockState state) {
        super(PianoFeature.PIANO_ENTITY.get(), pos, state);
        target = PianoPackets.Target.placed(pos);
    }

    public PianoPackets.Target target() { return target; }
    public BlockState renderedState() { return mountedState == null ? getBlockState() : mountedState; }

    void showMounted(int carrier, BlockState state) {
        if (target.carrier() != carrier) target = new PianoPackets.Target(carrier, worldPosition);
        mountedState = state;
    }

    void interact(ServerPlayer player, Action action) {
        if (!(level instanceof ServerLevel server)) return;
        if (session == null) session = new PianoSession(server, target, this::frame, this::moveLid);
        session.interact(player, action);
    }

    PianoPackets.Frame frame() {
        var state = getBlockState();
        var at = SableCompat.toWorld(level, worldPosition, GrandPianoBlock.soundboard(worldPosition, state));
        return new PianoPackets.Frame(at, state.getValue(GrandPianoBlock.OPEN));
    }

    public void toggleLid() {
        moveLid(Action.ROOF);
    }

    private void moveLid(Action action) {
        if (level instanceof ServerLevel) {
            level.setBlock(worldPosition, GrandPianoBlock.moveLid(getBlockState(), action), Block.UPDATE_ALL);
        }
    }

    @Nullable PianoSession session() { return session; }

    void tick() {
        if (session != null) session.tick();
    }

    @Override
    public void setRemoved() {
        if (session != null) session.close();
        super.setRemoved();
    }
}
