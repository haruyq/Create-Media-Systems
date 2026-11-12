package org.haruyq.createmediasystems.broadcast;

import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.Streams;

/** A pose supplied by the server lets listeners hear a tower even when its carrier is outside tracking range. */
public final class MovingBroadcastPackets {
    private static final StreamCodec<ByteBuf, Vec3> VECTOR = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    private static Client client;

    private MovingBroadcastPackets() {}

    public interface Client {
        void play(Play packet);
        void update(Update packet);
        void sync(Sync packet);
        void stop(Stop packet);
    }

    public static void client(Client handler) {
        client = handler;
    }

    public record Frame(int contraption, BlockPos head, Vec3 origin, Vec3 east, Vec3 up) {
        static final StreamCodec<ByteBuf, Frame> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Frame::contraption, BlockPos.STREAM_CODEC, Frame::head,
                VECTOR, Frame::origin, VECTOR, Frame::east, VECTOR, Frame::up, Frame::new);

        public static Vec3 mouth(BlockPos head) {
            return head.getCenter().add(0, .26, 0);
        }

        public static Frame at(int contraption, BlockPos head, UnaryOperator<Vec3> world) {
            Vec3 mouth = mouth(head);
            Vec3 origin = world.apply(mouth);
            return new Frame(contraption, head, origin, world.apply(mouth.add(1, 0, 0)).subtract(origin).normalize(),
                    world.apply(mouth.add(0, 1, 0)).subtract(origin).normalize());
        }

        public Vec3 towards(Vec3 listener) {
            Vec3 displacement = listener.subtract(origin);
            return new Vec3(displacement.dot(east), displacement.dot(up), displacement.dot(east.cross(up)));
        }
    }

    public record Signal(String url, float seconds, Optional<BlockPos> source, long load) {
        static final StreamCodec<ByteBuf, Signal> CODEC = StreamCodec.composite(
                StreamPackets.URL, Signal::url, ByteBufCodecs.FLOAT, Signal::seconds,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Signal::source, ByteBufCodecs.VAR_LONG, Signal::load, Signal::new);
    }

    public record Play(UUID id, Signal signal, Streams.Channel channel, BroadcastProfile profile, Frame frame)
            implements CustomPacketPayload {
        static final Type<Play> TYPE = new Type<>(CreateMediaSystems.id("play_moving_broadcast"));
        static final StreamCodec<ByteBuf, Play> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Play::id, Signal.CODEC, Play::signal,
                Streams.Channel.CODEC, Play::channel, BroadcastProfile.CODEC, Play::profile, Frame.CODEC, Play::frame, Play::new);

        @Override
        public Type<Play> type() {
            return TYPE;
        }
    }

    public record Update(UUID id, float seconds, float volume, Frame frame) implements CustomPacketPayload {
        static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("update_moving_broadcast"));
        static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Update::id, ByteBufCodecs.FLOAT, Update::seconds,
                ByteBufCodecs.FLOAT, Update::volume, Frame.CODEC, Update::frame, Update::new);

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    public record Sync(UUID id, float seconds) implements CustomPacketPayload {
        static final Type<Sync> TYPE = new Type<>(CreateMediaSystems.id("sync_moving_broadcast"));
        static final StreamCodec<ByteBuf, Sync> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Sync::id, ByteBufCodecs.FLOAT, Sync::seconds, Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public record Stop(UUID id) implements CustomPacketPayload {
        static final Type<Stop> TYPE = new Type<>(CreateMediaSystems.id("stop_moving_broadcast"));
        static final StreamCodec<ByteBuf, Stop> CODEC = UUIDUtil.STREAM_CODEC.map(Stop::new, Stop::id);

        @Override
        public Type<Stop> type() {
            return TYPE;
        }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Play.TYPE, Play.CODEC, (packet, context) -> client.play(packet));
        registrar.playToClient(Update.TYPE, Update.CODEC, (packet, context) -> client.update(packet));
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (packet, context) -> client.sync(packet));
        registrar.playToClient(Stop.TYPE, Stop.CODEC, (packet, context) -> client.stop(packet));
    }
}
