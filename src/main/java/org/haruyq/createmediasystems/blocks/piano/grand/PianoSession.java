package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets.Action;

final class PianoSession {
    static final int AUDIBLE_RANGE = 64;
    private static final double PLAYING_REACH = 8;
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final int MAX_EVENTS_PER_TICK = 384;
    private static final int UPDATE_INTERVAL = 10;
    private static final int BETWEEN_NOTES_TICKS = 20;
    private final ServerLevel level;
    private final PianoPackets.Target target;
    private final Supplier<PianoPackets.Frame> currentFrame;
    private final BooleanSupplier keyboardOpen;
    private final Consumer<Action> moveLid;
    private final Map<UUID, Take> performers = new LinkedHashMap<>();

    private static final class Take {
        final int id = SESSIONS.incrementAndGet();
        final PianoState keys = new PianoState();
        final Set<ServerPlayer> listeners = new HashSet<>();
        long budgetTick = Long.MIN_VALUE;
        long lastStrike = -BETWEEN_NOTES_TICKS - 1;
        int eventsThisTick;
    }

    PianoSession(ServerLevel level, PianoPackets.Target target, Supplier<PianoPackets.Frame> currentFrame,
            BooleanSupplier keyboardOpen, Consumer<Action> moveLid) {
        this.level = level;
        this.target = target;
        this.currentFrame = currentFrame;
        this.keyboardOpen = keyboardOpen;
        this.moveLid = moveLid;
    }

    int interact(ServerPlayer player, Action action) {
        var frame = currentFrame.get();
        if (!inReach(player, frame.origin())) return 0;
        if (action != Action.PLAY) {
            toggle(player, action);
            return 0;
        }
        if (!keyboardOpen.getAsBoolean()) return 0;
        var previous = performers.remove(player.getUUID());
        if (previous != null) end(player.getUUID(), previous);
        var take = new Take();
        performers.put(player.getUUID(), take);
        PacketDistributor.sendToPlayer(player, new PianoPackets.Open(target, take.id, frame));
        return take.id;
    }

    void play(ServerPlayer player, int session, List<PianoEvent> events) {
        var take = performers.get(player.getUUID());
        if (take == null || take.id != session || events.isEmpty()) return;
        var frame = currentFrame.get();
        if (!inReach(player, frame.origin())) {
            leave(player.getUUID());
            return;
        }
        long now = level.getGameTime();
        if (now != take.budgetTick) {
            take.budgetTick = now;
            take.eventsThisTick = 0;
        }
        take.eventsThisTick += events.size();
        if (take.eventsThisTick > MAX_EVENTS_PER_TICK) {
            leave(player.getUUID());
            return;
        }

        refreshAudience(player.getUUID(), take, frame);
        for (var event : events) {
            take.keys.apply(event);
            if (event.kind() == PianoEvent.Kind.ON) take.lastStrike = now;
        }
        broadcast(take, frame, events);
    }

    void command(ServerPlayer player, int session, Action action) {
        var take = performers.get(player.getUUID());
        if (take == null || take.id != session) return;
        if (action == Action.CLOSE || !inReach(player, currentFrame.get().origin())) leave(player.getUUID());
        else toggle(player, action);
    }

    private void toggle(ServerPlayer closer, Action action) {
        boolean closingKeyboard = action == Action.KEYBOARD_LID && keyboardOpen.getAsBoolean();
        moveLid.accept(action);
        if (!closingKeyboard) return;
        var origin = currentFrame.get().origin();
        long now = level.getGameTime();
        var caught = performers.entrySet().stream()
                .filter(entry -> entry.getValue().keys.anyKeyDown() || now - entry.getValue().lastStrike <= BETWEEN_NOTES_TICKS)
                .map(entry -> level.getPlayerByUUID(entry.getKey()))
                .filter(player -> player instanceof ServerPlayer server && inReach(server, origin))
                .toList();
        close();

        // Player-sourced damage would apply PvP and team rules to the fallboard.
        var source = new DamageSource(level.damageSources().genericKill().typeHolder()) {
            @Override
            public Component getLocalizedDeathMessage(LivingEntity victim) {
                if (victim == closer) {
                    return Component.translatable("death.attack.cms_piano_fallboard.self", victim.getDisplayName());
                }
                return Component.translatable("death.attack.cms_piano_fallboard", victim.getDisplayName(), closer.getDisplayName());
            }
        };
        caught.forEach(player -> player.hurt(source, Float.MAX_VALUE));
    }

    void tick() {
        if (performers.isEmpty()) return;
        var frame = currentFrame.get();
        boolean update = level.getGameTime() % UPDATE_INTERVAL == 0;
        var entries = performers.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var take = entry.getValue();
            if (!(level.getPlayerByUUID(entry.getKey()) instanceof ServerPlayer player) || !inReach(player, frame.origin())) {
                entries.remove();
                end(entry.getKey(), take);
            } else if (update) {
                refreshAudience(entry.getKey(), take, frame);
                PacketDistributor.sendToPlayer(player, new PianoPackets.Update(target, take.id, frame, false, List.of()));
                broadcast(take, frame, List.of());
            }
        }
    }

    private boolean inReach(ServerPlayer player, Vec3 origin) {
        return player.isAlive() && !player.isSpectator() && player.level() == level
                && SableCompat.eye(player, 1).distanceToSqr(origin) <= PLAYING_REACH * PLAYING_REACH;
    }

    private void refreshAudience(UUID performer, Take take, PianoPackets.Frame frame) {
        var end = new PianoPackets.End(target, take.id);
        take.listeners.removeIf(listener -> {
            if (listener.isRemoved() || listener.level() != level) return true;
            if (inRange(listener, frame.origin())) return false;
            PacketDistributor.sendToPlayer(listener, end);
            return true;
        });
        for (ServerPlayer listener : level.players()) {
            if (!listener.getUUID().equals(performer) && inRange(listener, frame.origin()) && take.listeners.add(listener)) {
                PacketDistributor.sendToPlayer(listener, new PianoPackets.Update(target, take.id, frame, true, take.keys.snapshot()));
            }
        }
    }

    private void broadcast(Take take, PianoPackets.Frame frame, List<PianoEvent> events) {
        var packet = new PianoPackets.Update(target, take.id, frame, false, events);
        take.listeners.forEach(listener -> PacketDistributor.sendToPlayer(listener, packet));
    }

    private static boolean inRange(ServerPlayer player, Vec3 origin) {
        return SableCompat.eye(player, 1).distanceToSqr(origin) <= AUDIBLE_RANGE * AUDIBLE_RANGE;
    }

    private void leave(UUID player) {
        var take = performers.remove(player);
        if (take != null) end(player, take);
    }

    private void end(UUID performer, Take take) {
        var end = new PianoPackets.End(target, take.id);
        if (level.getPlayerByUUID(performer) instanceof ServerPlayer player && !player.isRemoved()) {
            PacketDistributor.sendToPlayer(player, end);
        }
        for (var listener : take.listeners) {
            if (!listener.isRemoved() && listener.level() == level) PacketDistributor.sendToPlayer(listener, end);
        }
    }

    void close() {
        performers.forEach(this::end);
        performers.clear();
    }
}
