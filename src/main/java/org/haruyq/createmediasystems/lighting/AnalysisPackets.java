package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.StreamPackets;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class AnalysisPackets {
    public static final int BATCH_SIZE = 64;
    public static final UUID NONE = new UUID(0, 0);

    private AnalysisPackets() {
    }

    private static void uuid(ByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits()).writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    private static void frames(ByteBuf buf, List<AnalysisFrame> frames) {
        if (frames.size() > BATCH_SIZE) {
            throw new IllegalArgumentException("Too many analysis frames");
        }
        buf.writeByte(frames.size());
        frames.forEach(frame -> AnalysisFrame.CODEC.encode(buf, frame));
    }

    private static List<AnalysisFrame> frames(ByteBuf buf) {
        int count = buf.readUnsignedByte();
        if (count > BATCH_SIZE) {
            throw new DecoderException("Too many analysis frames");
        }
        List<AnalysisFrame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(AnalysisFrame.CODEC.decode(buf));
        }
        return List.copyOf(frames);
    }

    public record State(UUID source, UUID session, int generation, UUID owner, BlockPos pos, String track,
                        String next, int height, long millis, boolean active, int delayFrom, int delayTo,
                        long delayStart) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(CreateMediaSystems.id("analysis_state"));
        public static final StreamCodec<ByteBuf, State> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            buf.writeInt(value.generation);
            uuid(buf, value.owner);
            BlockPos.STREAM_CODEC.encode(buf, value.pos);
            StreamPackets.URL.encode(buf, value.track);
            StreamPackets.URL.encode(buf, value.next);
            buf.writeInt(value.height).writeLong(value.millis).writeBoolean(value.active)
                    .writeInt(value.delayFrom).writeInt(value.delayTo).writeLong(value.delayStart);
        }, buf -> new State(uuid(buf), uuid(buf), buf.readInt(), uuid(buf), BlockPos.STREAM_CODEC.decode(buf),
                StreamPackets.URL.decode(buf), StreamPackets.URL.decode(buf), buf.readInt(), buf.readLong(), buf.readBoolean(),
                buf.readInt(), buf.readInt(), buf.readLong()));

        public State(UUID source, UUID session, int generation, UUID owner, BlockPos pos, String track,
                     String next, int height, long millis, boolean active) {
            this(source, session, generation, owner, pos, track, next, height, millis, active,
                    AnalysisDelay.INITIAL.from(), AnalysisDelay.INITIAL.to(), AnalysisDelay.INITIAL.start());
        }

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    public record Status(UUID source, UUID session, boolean ready, long millis, int lagMillis, long clientSentNanos,
                         int upstreamMillis) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(CreateMediaSystems.id("analysis_status"));
        public static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            buf.writeBoolean(value.ready).writeLong(value.millis).writeInt(value.lagMillis)
                    .writeLong(value.clientSentNanos).writeInt(value.upstreamMillis);
        }, buf -> new Status(uuid(buf), uuid(buf), buf.readBoolean(), buf.readLong(), buf.readInt(), buf.readLong(), buf.readInt()));

        public Status(UUID source, UUID session, boolean ready, long millis) {
            this(source, session, ready, millis, -1, 0, 0);
        }

        public Status(UUID source, UUID session, boolean ready, long millis, int lagMillis) {
            this(source, session, ready, millis, lagMillis, 0, 0);
        }

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }

    public record Clock(UUID source, UUID session, long echoClientSent, long serverMediaMillis) implements CustomPacketPayload {
        public static final Type<Clock> TYPE = new Type<>(CreateMediaSystems.id("analysis_clock"));
        public static final StreamCodec<ByteBuf, Clock> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            buf.writeLong(value.echoClientSent).writeLong(value.serverMediaMillis);
        }, buf -> new Clock(uuid(buf), uuid(buf), buf.readLong(), buf.readLong()));

        @Override
        public Type<Clock> type() {
            return TYPE;
        }
    }

    public record Upload(UUID source, UUID session, int generation, long sequence, String track,
                         boolean prepared, List<AnalysisFrame> frames) implements CustomPacketPayload {
        public static final Type<Upload> TYPE = new Type<>(CreateMediaSystems.id("analysis_upload"));
        public static final StreamCodec<ByteBuf, Upload> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            buf.writeInt(value.generation).writeLong(value.sequence);
            StreamPackets.URL.encode(buf, value.track);
            buf.writeBoolean(value.prepared);
            AnalysisPackets.frames(buf, value.frames);
        }, buf -> new Upload(uuid(buf), uuid(buf), buf.readInt(), buf.readLong(), StreamPackets.URL.decode(buf),
                buf.readBoolean(), AnalysisPackets.frames(buf)));

        @Override
        public Type<Upload> type() {
            return TYPE;
        }
    }

    public record Batch(UUID source, UUID session, String track, boolean prepared,
                        List<AnalysisFrame> frames) implements CustomPacketPayload {
        public static final Type<Batch> TYPE = new Type<>(CreateMediaSystems.id("analysis_batch"));
        public static final StreamCodec<ByteBuf, Batch> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            StreamPackets.URL.encode(buf, value.track);
            buf.writeBoolean(value.prepared);
            AnalysisPackets.frames(buf, value.frames);
        }, buf -> new Batch(uuid(buf), uuid(buf), StreamPackets.URL.decode(buf), buf.readBoolean(), AnalysisPackets.frames(buf)));

        @Override
        public Type<Batch> type() {
            return TYPE;
        }
    }

    public record Accepted(UUID source, UUID session, int generation, long sequence) implements CustomPacketPayload {
        public static final Type<Accepted> TYPE = new Type<>(CreateMediaSystems.id("analysis_accepted"));
        public static final StreamCodec<ByteBuf, Accepted> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            buf.writeInt(value.generation).writeLong(value.sequence);
        }, buf -> new Accepted(uuid(buf), uuid(buf), buf.readInt(), buf.readLong()));

        @Override
        public Type<Accepted> type() {
            return TYPE;
        }
    }

    public record Received(UUID source, UUID session, String track, long millis) implements CustomPacketPayload {
        public static final Type<Received> TYPE = new Type<>(CreateMediaSystems.id("analysis_received"));
        public static final StreamCodec<ByteBuf, Received> CODEC = StreamCodec.of((buf, value) -> {
            uuid(buf, value.source);
            uuid(buf, value.session);
            StreamPackets.URL.encode(buf, value.track);
            buf.writeLong(value.millis);
        }, buf -> new Received(uuid(buf), uuid(buf), StreamPackets.URL.decode(buf), buf.readLong()));

        @Override
        public Type<Received> type() {
            return TYPE;
        }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(State.TYPE, State.CODEC, (state, context) -> AnalysisPlayback.state(state));
        registrar.playToClient(Clock.TYPE, Clock.CODEC, (clock, context) -> AnalysisPlayback.clock(clock));
        registrar.playToClient(Batch.TYPE, Batch.CODEC, (batch, context) -> AnalysisPlayback.batch(batch));
        registrar.playToClient(Accepted.TYPE, Accepted.CODEC, (accepted, context) -> AnalysisPlayback.accepted(accepted));
        registrar.playToServer(Status.TYPE, Status.CODEC,
                (status, context) -> AnalysisCoordinator.status((ServerPlayer) context.player(), status));
        registrar.playToServer(Upload.TYPE, Upload.CODEC,
                (upload, context) -> AnalysisCoordinator.upload((ServerPlayer) context.player(), upload));
        registrar.playToServer(Received.TYPE, Received.CODEC,
                (received, context) -> AnalysisCoordinator.received((ServerPlayer) context.player(), received));
    }
}
