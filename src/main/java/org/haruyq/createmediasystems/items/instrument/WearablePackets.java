package org.haruyq.createmediasystems.items.instrument;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class WearablePackets {
    public static final int CLOSE = -1;
    private static final StreamCodec<ByteBuf, List<Integer>> EVENTS = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(104));

    public interface Client {
        void open(Open packet);
        void update(Update packet);
        void guitar(GuitarOutput packet);
    }

    private static Client client;

    private WearablePackets() {}

    public static void client(Client receiver) { client = receiver; }

    public record Open(int session, boolean mainHand, Kind kind) implements CustomPacketPayload {
        static final Type<Open> TYPE = new Type<>(CreateMediaSystems.id("wearable_open"));
        static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Open::session,
                ByteBufCodecs.BOOL, Open::mainHand, ByteBufCodecs.VAR_INT.map(Kind::byId, kind -> kind.wireId), Open::kind, Open::new);
        @Override public Type<Open> type() { return TYPE; }
    }

    public record Input(int session, int message) implements CustomPacketPayload {
        static final Type<Input> TYPE = new Type<>(CreateMediaSystems.id("wearable_input"));
        static final StreamCodec<ByteBuf, Input> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Input::session,
                ByteBufCodecs.VAR_INT, Input::message, Input::new);
        @Override public Type<Input> type() { return TYPE; }
    }

    public record Update(UUID player, int session, int flags, KeytarFinish finish, boolean snapshot, List<Integer> events)
            implements CustomPacketPayload {
        static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("wearable_update"));
        static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Update::player,
                ByteBufCodecs.VAR_INT, Update::session, ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), Update::flags,
                KeytarFinish.STREAM_CODEC, Update::finish, ByteBufCodecs.BOOL, Update::snapshot, EVENTS, Update::events, Update::new);
        public Kind kind() { return Kind.fromFlags(flags); }
        @Override public Type<Update> type() { return TYPE; }
    }

    public record GuitarInput(int session, byte[] frame) implements CustomPacketPayload {
        static final Type<GuitarInput> TYPE = new Type<>(CreateMediaSystems.id("guitar_input"));
        static final StreamCodec<ByteBuf, GuitarInput> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GuitarInput::session, GuitarFrame.CODEC, GuitarInput::frame, GuitarInput::new);
        @Override public Type<GuitarInput> type() { return TYPE; }
    }

    public record GuitarOutput(UUID player, int session, byte[] frame) implements CustomPacketPayload {
        static final Type<GuitarOutput> TYPE = new Type<>(CreateMediaSystems.id("guitar_output"));
        static final StreamCodec<ByteBuf, GuitarOutput> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, GuitarOutput::player,
                ByteBufCodecs.VAR_INT, GuitarOutput::session, GuitarFrame.CODEC, GuitarOutput::frame, GuitarOutput::new);
        @Override public Type<GuitarOutput> type() { return TYPE; }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(GuitarInput.TYPE, GuitarInput.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) WearableInstruments.guitar(player, packet.session, packet.frame);
        });
        registrar.playToClient(GuitarOutput.TYPE, GuitarOutput.CODEC, (packet, context) -> { if (client != null) client.guitar(packet); });
        registrar.playToClient(Open.TYPE, Open.CODEC, (packet, context) -> { if (client != null) client.open(packet); });
        registrar.playToClient(Update.TYPE, Update.CODEC, (packet, context) -> { if (client != null) client.update(packet); });
        registrar.playToServer(Input.TYPE, Input.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) WearableInstruments.input(player, packet.session, packet.message);
        });
    }
}
