package org.haruyq.createmediasystems.items.instrument;

import java.util.List;
import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;

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
    }

    private static Client client;

    private WearablePackets() {}

    public static void client(Client receiver) { client = receiver; }

    public record Open(int session, boolean mainHand, boolean keytar) implements CustomPacketPayload {
        static final Type<Open> TYPE = new Type<>(CreateMediaSystems.id("wearable_open"));
        static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Open::session,
                ByteBufCodecs.BOOL, Open::mainHand, ByteBufCodecs.BOOL, Open::keytar, Open::new);
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
        @Override public Type<Update> type() { return TYPE; }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Open.TYPE, Open.CODEC, (packet, context) -> { if (client != null) client.open(packet); });
        registrar.playToClient(Update.TYPE, Update.CODEC, (packet, context) -> { if (client != null) client.update(packet); });
        registrar.playToServer(Input.TYPE, Input.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) WearableInstruments.input(player, packet.session, packet.message);
        });
    }
}
