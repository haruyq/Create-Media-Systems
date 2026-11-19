package org.haruyq.createmediasystems.blocks.radio;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.StreamPackets;
import org.haruyq.createmediasystems.stream.StreamPackets.Target;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** What a transmitter tells the receivers tuned to it (see RadioTransmitterBlock.Transmitter#relay), and its settings. */
public final class RadioPackets {
    /** What a client does with a station's words: RadioAudio, which sets it as the client starts. */
    private static Client client;

    private RadioPackets() {
    }

    public interface Client {
        void play(Play packet);

        void tune(Tune packet);

        void signal(Signal packet);

        void sync(Sync packet);

        void stop(Stop packet);
    }

    public static void client(Client handler) {
        client = handler;
    }

    /**
     * Server → client: load the track {@code url} that {@code station} sends, from {@code seconds} in, as {@code load}
     * of its source (see Streams.Load); it plays once Sync says so.
     */
    public record Play(Target.Radio station, String url, float seconds, long load) implements CustomPacketPayload {
        static final Type<Play> TYPE = new Type<>(CreateMediaSystems.id("play_radio"));
        static final StreamCodec<ByteBuf, Play> CODEC = StreamCodec.composite(
                Target.Radio.CODEC, Play::station, StreamPackets.URL, Play::url,
                ByteBufCodecs.FLOAT, Play::seconds, ByteBufCodecs.VAR_LONG, Play::load, Play::new);

        @Override
        public Type<Play> type() {
            return TYPE;
        }
    }

    /**
     * Server → client, every relay: {@code station} is still received, its track {@code seconds} in, at {@code volume}
     * (0..1: the station's own, before what its signal here takes off it).
     */
    public record Tune(Target.Radio station, float seconds, float volume) implements CustomPacketPayload {
        static final Type<Tune> TYPE = new Type<>(CreateMediaSystems.id("tune_radio"));
        static final StreamCodec<ByteBuf, Tune> CODEC = StreamCodec.composite(
                Target.Radio.CODEC, Tune::station, ByteBufCodecs.FLOAT, Tune::seconds,
                ByteBufCodecs.FLOAT, Tune::volume, Tune::new);

        @Override
        public Type<Tune> type() {
            return TYPE;
        }
    }

    /**
     * Server → client, every relay while their receiver is on: how strong (0..1) the station of its frequency is
     * received where they are; 0 for none.
     */
    public record Signal(float strength) implements CustomPacketPayload {
        static final Type<Signal> TYPE = new Type<>(CreateMediaSystems.id("radio_signal"));
        static final StreamCodec<ByteBuf, Signal> CODEC = StreamCodec.composite(ByteBufCodecs.FLOAT, Signal::strength, Signal::new);

        @Override
        public Type<Signal> type() {
            return TYPE;
        }
    }

    public record Sync(Target.Radio station, float seconds) implements CustomPacketPayload {
        static final Type<Sync> TYPE = new Type<>(CreateMediaSystems.id("sync_radio"));
        static final StreamCodec<ByteBuf, Sync> CODEC = StreamCodec.composite(
                Target.Radio.CODEC, Sync::station, ByteBufCodecs.FLOAT, Sync::seconds, Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public record Stop(Target.Radio station) implements CustomPacketPayload {
        static final Type<Stop> TYPE = new Type<>(CreateMediaSystems.id("stop_radio"));
        static final StreamCodec<ByteBuf, Stop> CODEC = Target.Radio.CODEC.map(Stop::new, Stop::station);

        @Override
        public Type<Stop> type() {
            return TYPE;
        }
    }

    /** Client → server: the transmitter at {@code transmitter} sends on {@code frequency} (tenths of a MHz). */
    public record SetFrequency(BlockPos transmitter, int frequency) implements CustomPacketPayload {
        static final Type<SetFrequency> TYPE = new Type<>(CreateMediaSystems.id("set_radio_frequency"));
        static final StreamCodec<ByteBuf, SetFrequency> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetFrequency::transmitter, ByteBufCodecs.VAR_INT, SetFrequency::frequency,
                SetFrequency::new);

        @Override
        public Type<SetFrequency> type() {
            return TYPE;
        }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Play.TYPE, Play.CODEC, (packet, context) -> client.play(packet));
        registrar.playToClient(Tune.TYPE, Tune.CODEC, (packet, context) -> client.tune(packet));
        registrar.playToClient(Signal.TYPE, Signal.CODEC, (packet, context) -> client.signal(packet));
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (packet, context) -> client.sync(packet));
        registrar.playToClient(Stop.TYPE, Stop.CODEC, (packet, context) -> client.stop(packet));
        registrar.playToServer(SetFrequency.TYPE, SetFrequency.CODEC,
                (packet, context) -> RadioTransmitterBlock.setFrequency((ServerPlayer) context.player(), packet));
    }
}
