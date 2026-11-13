package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;
import org.jetbrains.annotations.Nullable;

final class PianoSession {
    static final int AUDIBLE_RANGE = 64;
    private static final double PLAYING_REACH = 8;
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final int MAX_EVENTS_PER_TICK = 384;
    private static final int UPDATE_INTERVAL = 10;

    private final ServerLevel level;
    private final PianoPackets.Target target;
    private final Supplier<PianoPackets.Frame> currentFrame;
    private final Consumer<Action> moveLid;
    private final Set<ServerPlayer> listeners = new HashSet<>();
    @Nullable private UUID performer;
    private PianoState keys = new PianoState();
    private int session, eventsThisTick;
    private long budgetTick = Long.MIN_VALUE;

    PianoSession(ServerLevel level, PianoPackets.Target target, Supplier<PianoPackets.Frame> currentFrame, Consumer<Action> moveLid) {
        this.level = level;
        this.target = target;
        this.currentFrame = currentFrame;
        this.moveLid = moveLid;
    }

    void interact(ServerPlayer player, Action action) {
        var frame = currentFrame.get();
        if (!inReach(player, frame.origin())) return;
        if (performer != null && !performer.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("piano.busy"), true);
            return;
        }
        if (action != Action.PLAY) {
            toggle(action);
            return;
        }
        close();
        session = SESSIONS.incrementAndGet();
        performer = player.getUUID();
        keys = new PianoState();
        budgetTick = Long.MIN_VALUE;
        PacketDistributor.sendToPlayer(player, new PianoPackets.Open(target, session, frame));
    }

    void play(ServerPlayer player, int session, List<PianoEvent> events) {
        if (!player.getUUID().equals(performer) || this.session != session || events.isEmpty()) return;
        var frame = currentFrame.get();
        if (!inReach(player, frame.origin())) {
            close();
            return;
        }
        long now = level.getGameTime();
        if (now != budgetTick) {
            budgetTick = now;
            eventsThisTick = 0;
        }
        eventsThisTick += events.size();
        if (eventsThisTick > MAX_EVENTS_PER_TICK) {
            close();
            return;
        }

        refreshAudience(frame);
        events.forEach(keys::apply);
        var update = new PianoPackets.Update(target, session, frame, false, events);
        listeners.forEach(listener -> PacketDistributor.sendToPlayer(listener, update));
    }

    void command(ServerPlayer player, int session, Action action) {
        if (this.session != session || !player.getUUID().equals(performer)) return;
        if (action == Action.CLOSE || !inReach(player, currentFrame.get().origin())) {
            close();
        } else {
            toggle(action);
        }
    }

    private void toggle(Action action) {
        moveLid.accept(action);
        if (action == Action.KEYBOARD_LID) close();
    }

    void tick() {
        if (performer == null) return;
        var frame = currentFrame.get();
        if (!(level.getPlayerByUUID(performer) instanceof ServerPlayer player) || !inReach(player, frame.origin())) {
            close();
        } else if (level.getGameTime() % UPDATE_INTERVAL == 0) {
            refreshAudience(frame);
            var update = new PianoPackets.Update(target, session, frame, false, List.of());
            PacketDistributor.sendToPlayer(player, update);
            listeners.forEach(listener -> PacketDistributor.sendToPlayer(listener, update));
        }
    }

    private boolean inReach(ServerPlayer player, Vec3 origin) {
        return player.isAlive() && !player.isSpectator() && player.level() == level
                && SableCompat.eye(player, 1).distanceToSqr(origin) <= PLAYING_REACH * PLAYING_REACH;
    }

    private void refreshAudience(PianoPackets.Frame frame) {
        var end = new PianoPackets.End(target, session);
        listeners.removeIf(listener -> {
            if (listener.isRemoved() || listener.level() != level) return true;
            if (inRange(listener, frame.origin())) return false;
            PacketDistributor.sendToPlayer(listener, end);
            return true;
        });
        for (ServerPlayer listener : level.players()) {
            if (!listener.getUUID().equals(performer) && inRange(listener, frame.origin()) && listeners.add(listener)) {
                PacketDistributor.sendToPlayer(listener, new PianoPackets.Update(target, session, frame, true, keys.snapshot()));
            }
        }
    }

    private static boolean inRange(ServerPlayer player, Vec3 origin) {
        return SableCompat.eye(player, 1).distanceToSqr(origin) <= AUDIBLE_RANGE * AUDIBLE_RANGE;
    }

    void close() {
        if (performer == null) return;
        var end = new PianoPackets.End(target, session);
        if (level.getPlayerByUUID(performer) instanceof ServerPlayer player && !player.isRemoved()) {
            PacketDistributor.sendToPlayer(player, end);
        }
        for (ServerPlayer listener : listeners) {
            if (!listener.isRemoved() && listener.level() == level) PacketDistributor.sendToPlayer(listener, end);
        }
        listeners.clear();
        performer = null;
    }
}
