package org.haruyq.createmediasystems.blocks.broadcast;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.stream.EmitterRange;
import org.haruyq.createmediasystems.stream.MediaSource;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

final class MovingBroadcast {
    private final StreamPackets.Target.Mobile target = new StreamPackets.Target.Mobile(UUID.randomUUID());
    private boolean active;

    void relay(ServerLevel level, MovingBroadcastPackets.Frame frame, @Nullable BlockPos link,
            @Nullable MediaSource.Keyed aboard, Streams.Channel channel, float volume, BroadcastProfile profile,
            boolean refresh) {
        var playing = aboard == null ? link == null ? null : Streams.source(level, link) : aboard.nowPlaying();
        if (playing == null || volume <= 0) {
            stop();
            return;
        }
        if (active && !refresh) return;
        active = true;
        double reach = profile.reach + EmitterRange.DROP_MARGIN;
        double reachSquared = reach * reach;
        var audience = level.players().stream()
                .filter(player -> SableCompat.eye(player, 1).distanceToSqr(frame.origin()) <= reachSquared)
                .toList();
        if (aboard != null) {
            Streams.relay(level, this, target, aboard, audience,
                    (source, load) -> play(source, load, null, channel, profile, frame));
        } else {
            Streams.relayMoving(level, this, target, link, channel, profile, audience,
                    (source, load) -> play(source, load, link, channel, profile, frame));
        }
        var update = new MovingBroadcastPackets.Update(target.id(), seconds(playing), volume, frame);
        audience.forEach(player -> PacketDistributor.sendToPlayer(player, update));
    }

    private MovingBroadcastPackets.Play play(Streams.Source source, long load, @Nullable BlockPos link,
            Streams.Channel channel, BroadcastProfile profile, MovingBroadcastPackets.Frame frame) {
        var signal = new MovingBroadcastPackets.Signal(source.url(), seconds(source), Optional.ofNullable(link), load);
        return new MovingBroadcastPackets.Play(target.id(), signal, channel, profile, frame);
    }

    private static float seconds(Streams.Source source) {
        return source.ticks() / (float) PlaybackClock.TICKS_PER_SECOND;
    }

    void stop() {
        if (active) {
            Streams.stop(this);
            active = false;
        }
    }
}
