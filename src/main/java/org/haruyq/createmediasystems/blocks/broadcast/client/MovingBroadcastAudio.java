package org.haruyq.createmediasystems.blocks.broadcast.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.broadcast.BroadcastOcclusion;
import org.haruyq.createmediasystems.blocks.broadcast.MovingBroadcastPackets;
import org.haruyq.createmediasystems.blocks.broadcast.MovingBroadcastPackets.Frame;
import org.haruyq.createmediasystems.playback.ClientStreams;
import org.haruyq.createmediasystems.playback.StreamSound;
import org.haruyq.createmediasystems.playback.StreamSpec;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

/** Uses the tracked structure's transform nearby and server poses beyond the structure's tracking distance. */
final class MovingBroadcastAudio implements MovingBroadcastPackets.Client {
    private static final int EXPIRE_TICKS = 4 * Streams.RELAY_TICKS;
    private static final int FORGET_TICKS = 20 * 60;
    private final Map<UUID, Playing> stations = new HashMap<>();

    static void register() {
        var audio = new MovingBroadcastAudio();
        MovingBroadcastPackets.client(audio);
        NeoForge.EVENT_BUS.addListener(audio::tick);
    }

    @Override
    public void play(MovingBroadcastPackets.Play packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        stop(packet.id());
        Playing playing = new Playing(mc.level, packet);
        stations.put(packet.id(), playing);
        playing.updatePosition(mc);
        playing.start();
    }

    @Override
    public void update(MovingBroadcastPackets.Update packet) {
        var playing = stations.get(packet.id());
        if (playing == null) return;
        playing.before = playing.frame;
        playing.frame = packet.frame();
        playing.volume = packet.volume();
        playing.seconds = packet.seconds();
        playing.receivedAt = playing.level.getGameTime();
    }

    @Override
    public void sync(MovingBroadcastPackets.Sync packet) {
        var playing = stations.get(packet.id());
        if (playing != null) playing.slot.sync(packet.seconds());
    }

    @Override
    public void stop(MovingBroadcastPackets.Stop packet) {
        stop(packet.id());
    }

    private void stop(UUID id) {
        Playing playing = stations.remove(id);
        if (playing != null) playing.slot.stop();
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        stations.values().removeIf(playing -> {
            if (mc.level != playing.level) {
                playing.slot.stop();
                return true;
            }
            if (!playing.present()) {
                playing.slot.stop();
                return playing.level.getGameTime() - playing.receivedAt >= FORGET_TICKS;
            }
            playing.updatePosition(mc);
            if (!playing.slot.playing() && playing.level.getGameTime() - playing.startedAt >= EXPIRE_TICKS) {
                playing.start();
            }
            return false;
        });
    }

    private static final class Playing {
        final ClientLevel level;
        final MovingBroadcastPackets.Play play;
        final ClientStreams.Slot slot;
        Frame before, frame;
        long startedAt, receivedAt;
        volatile float seconds;
        volatile double gain;
        volatile Vec3 origin;
        volatile BroadcastAudioStream.Path path = new BroadcastAudioStream.Path(0, false);
        float volume = 1;

        Playing(ClientLevel level, MovingBroadcastPackets.Play play) {
            this.level = level;
            this.play = play;
            before = frame = play.frame();
            seconds = play.signal().seconds();
            origin = frame.origin();
            slot = new ClientStreams.Slot("moving broadcast " + play.id());
            receivedAt = level.getGameTime();
        }

        boolean present() {
            return level.getGameTime() - receivedAt < EXPIRE_TICKS;
        }

        private double positionSeconds() {
            return seconds + Math.max(0, level.getGameTime() - receivedAt) / (double) PlaybackClock.TICKS_PER_SECOND;
        }

        void start() {
            startedAt = level.getGameTime();
            var emitter = new StreamSpec.Emitter(() -> origin, null, this::present,
                    this::present, () -> gain, null, false);
            var signal = play.signal();
            var spec = new StreamSpec(signal.url(), positionSeconds(), play.channel(), SoundSource.RECORDS, emitter,
                    length -> Minecraft.getInstance().execute(() -> {
                        if (present() && Minecraft.getInstance().level == level) {
                            PacketDistributor.sendToServer(new StreamPackets.Started(
                                    new StreamPackets.Target.Mobile(play.id()), (float) length));
                        }
                    })).decorated(audio -> BroadcastAudioStream.wrap(audio, play.profile(), () -> path));
            if (signal.source().isPresent()) {
                spec = spec.from(signal.source().get(), signal.load(), null);
            } else {
                spec = spec.movingDisc(play.id(), this::positionSeconds);
            }
            slot.play(new StreamSound(spec));
        }

        void updatePosition(Minecraft mc) {
            Frame current = frame;
            if (frame.contraption() >= 0 && level.getEntity(frame.contraption()) instanceof AbstractContraptionEntity carrier) {
                BlockPos plot = carrier.blockPosition();
                current = Frame.at(frame.contraption(), frame.head(),
                        local -> SableCompat.toRenderWorld(level, plot, carrier.toGlobalVector(local, 1)));
            } else if (frame.contraption() < 0 && SableCompat.containing(level, frame.head()) != null) {
                current = Frame.at(-1, frame.head(), local -> SableCompat.toRenderWorld(level, frame.head(), local));
            } else {
                double blend = Math.clamp((level.getGameTime() - receivedAt) / (double) Streams.RELAY_TICKS, 0, 1);
                Vec3 at = before.origin().lerp(frame.origin(), blend);
                current = new Frame(frame.contraption(), frame.head(), at, frame.east(), frame.up());
            }
            origin = current.origin();
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            double distance = camera.distanceTo(origin);
            boolean blocked = path.obstructed();
            if (mc.player != null && (level.getGameTime() % 5 == 0 || path.metres() == 0)) {
                blocked = BroadcastOcclusion.obstructed(mc.player, camera, origin);
            }
            path = new BroadcastAudioStream.Path(distance, blocked);
            gain = volume * play.profile().gain(current.towards(camera)) * path.transmission();
        }
    }
}
