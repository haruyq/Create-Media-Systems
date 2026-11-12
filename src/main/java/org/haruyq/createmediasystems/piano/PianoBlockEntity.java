package org.haruyq.createmediasystems.piano;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class PianoBlockEntity extends BlockEntity {
    public static final int AUDIBLE_RANGE = 64;
    private static final double PLAYING_REACH = 8;
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final int MAX_EVENTS_PER_TICK = 384;

    private final Set<ServerPlayer> listeners = new HashSet<>();
    @Nullable private UUID performer;
    private PianoState keys = new PianoState();
    private int session, eventsThisTick;
    private long budgetTick = Long.MIN_VALUE;

    public PianoBlockEntity(BlockPos pos, BlockState state) {
        super(PianoFeature.PIANO_ENTITY.get(), pos, state);
    }

    public boolean canOperate(ServerPlayer player) {
        return performer == null || performer.equals(player.getUUID());
    }

    void open(ServerPlayer player) {
        if (!inReach(player)) return;
        if (!canOperate(player)) {
            player.displayClientMessage(Component.translatable("piano.busy"), true);
            return;
        }
        if (performer != null) close(player.serverLevel());
        session = SESSIONS.incrementAndGet();
        performer = player.getUUID();
        keys = new PianoState();
        budgetTick = Long.MIN_VALUE;
        PacketDistributor.sendToPlayer(player, new PianoPackets.Open(worldPosition, session, origin()));
    }

    private boolean inReach(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.level() == level
                && SableCompat.eye(player, 1).distanceToSqr(origin()) <= PLAYING_REACH * PLAYING_REACH;
    }

    private Vec3 origin() {
        return SableCompat.toWorld(level, worldPosition, GrandPianoBlock.soundboard(worldPosition, getBlockState()));
    }

    public void toggleLid() {
        if (level instanceof ServerLevel) {
            level.setBlock(worldPosition, getBlockState().cycle(GrandPianoBlock.OPEN), Block.UPDATE_ALL);
        }
    }

    void play(ServerPlayer player, int session, List<PianoEvent> events) {
        if (!player.getUUID().equals(performer) || this.session != session || events.isEmpty()) return;
        if (!inReach(player)) {
            close(player.serverLevel());
            return;
        }
        long now = player.level().getGameTime();
        if (now != budgetTick) {
            budgetTick = now;
            eventsThisTick = 0;
        }
        eventsThisTick += events.size();
        if (eventsThisTick > MAX_EVENTS_PER_TICK) {
            close(player.serverLevel());
            return;
        }

        Vec3 origin = origin();
        refreshAudience(player.serverLevel(), origin);
        events.forEach(keys::apply);
        var update = new PianoPackets.Update(worldPosition, session, origin, false, events);
        listeners.forEach(listener -> PacketDistributor.sendToPlayer(listener, update));
    }

    void command(ServerPlayer player, int session, boolean toggleLid) {
        if (this.session != session || !player.getUUID().equals(performer)) return;
        if (toggleLid && inReach(player)) toggleLid();
        else close(player.serverLevel());
    }

    void tick(ServerLevel level) {
        if (performer == null) return;
        if (!(level.getPlayerByUUID(performer) instanceof ServerPlayer player) || !inReach(player)) {
            close(level);
        } else if (level.getGameTime() % 10 == 0) {
            Vec3 origin = origin();
            refreshAudience(level, origin);
            var update = new PianoPackets.Update(worldPosition, session, origin, false, List.of());
            listeners.forEach(listener -> PacketDistributor.sendToPlayer(listener, update));
        }
    }

    private void refreshAudience(ServerLevel level, Vec3 origin) {
        var end = new PianoPackets.End(worldPosition, session);
        listeners.removeIf(listener -> {
            if (listener.isRemoved() || listener.level() != level) return true;
            if (inRange(listener, origin)) return false;
            PacketDistributor.sendToPlayer(listener, end);
            return true;
        });
        for (ServerPlayer listener : level.players()) {
            if (!listener.getUUID().equals(performer) && inRange(listener, origin) && listeners.add(listener)) {
                PacketDistributor.sendToPlayer(listener, new PianoPackets.Update(worldPosition, session, origin, true, keys.snapshot()));
            }
        }
    }

    private static boolean inRange(ServerPlayer player, Vec3 origin) {
        return SableCompat.eye(player, 1).distanceToSqr(origin) <= AUDIBLE_RANGE * AUDIBLE_RANGE;
    }

    private void close(ServerLevel level) {
        if (performer == null) return;
        var end = new PianoPackets.End(worldPosition, session);
        if (level.getPlayerByUUID(performer) instanceof ServerPlayer player && !player.isRemoved()) {
            PacketDistributor.sendToPlayer(player, end);
        }
        for (ServerPlayer listener : listeners) {
            if (!listener.isRemoved() && listener.level() == level) PacketDistributor.sendToPlayer(listener, end);
        }
        listeners.clear();
        performer = null;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) close(server);
        super.setRemoved();
    }
}
