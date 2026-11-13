package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.Streams;
import org.jetbrains.annotations.Nullable;

public final class KeyboardPackets {
    public static final int BATCH = 96;
    static final int MAX_SPEAKERS = 8;
    private static final StreamCodec<ByteBuf, List<KeyboardMidi>> INPUT = KeyboardMidi.CODEC.apply(ByteBufCodecs.list(BATCH));
    private static final StreamCodec<ByteBuf, List<KeyboardMidi>> STATE = KeyboardMidi.CODEC.apply(ByteBufCodecs.list(4300));
    private static final StreamCodec<ByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    private static Client client;

    private KeyboardPackets() {}

    public enum Action {
        CLOSE, POWER, VOLUME, WHEEL, FONT, MAIN, LAYER, LOWER, LAYER_ON, SPLIT_ON, SPLIT_KEY,
        TRANSPOSE, TOUCH, REVERB, CHORUS, METRONOME, BPM, BEATS, POLYPHONY, RECORD, PLAY,
        RACK, STAND, SOUND_MODE, PANIC, PEDAL
    }

    public interface Client {
        void open(Open packet);
        void update(Update packet);
        void end(End packet);
    }

    public static void client(Client receiver) { client = receiver; }

    public record Target(int carrier, BlockPos pos) {
        static final StreamCodec<ByteBuf, Target> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Target::carrier, BlockPos.STREAM_CODEC, Target::pos, Target::new);

        public static Target placed(BlockPos pos) { return new Target(-1, pos); }
        public boolean mounted() { return carrier >= 0; }
    }

    public record Frame(Vec3 left, Vec3 right) {
        static final StreamCodec<ByteBuf, Frame> CODEC = StreamCodec.composite(VEC, Frame::left, VEC, Frame::right, Frame::new);
        public Vec3 centre() { return left.lerp(right, .5); }
    }

    public record Output(BlockPos pos, Streams.Sound sound, float volume) {
        static final StreamCodec<ByteBuf, Output> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Output::pos, Streams.Sound.CODEC, Output::sound,
                ByteBufCodecs.FLOAT, Output::volume, Output::new);
    }

    public record Routing(boolean external, List<Output> outputs) {
        public static final Routing INTERNAL = new Routing(false, List.of());
        static final StreamCodec<ByteBuf, Routing> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Routing::external, Output.CODEC.apply(ByteBufCodecs.list(MAX_SPEAKERS)),
                Routing::outputs, Routing::new);
    }

    public record Open(Target target, int token, boolean browser) implements CustomPacketPayload {
        static final Type<Open> TYPE = new Type<>(CreateMediaSystems.id("keyboard_open"));
        static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(Target.CODEC, Open::target,
                ByteBufCodecs.VAR_INT, Open::token, ByteBufCodecs.BOOL, Open::browser, Open::new);
        @Override public Type<Open> type() { return TYPE; }
    }

    public record Update(Target target, Frame frame, CompoundTag view, boolean snapshot, List<KeyboardMidi> events, Routing routing)
            implements CustomPacketPayload {
        static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("keyboard_update"));
        static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(Target.CODEC, Update::target,
                Frame.CODEC, Update::frame, ByteBufCodecs.COMPOUND_TAG, Update::view, ByteBufCodecs.BOOL, Update::snapshot,
                STATE, Update::events, Routing.CODEC, Update::routing, Update::new);
        @Override public Type<Update> type() { return TYPE; }
    }

    public record End(Target target, int token) implements CustomPacketPayload {
        static final Type<End> TYPE = new Type<>(CreateMediaSystems.id("keyboard_end"));
        static final StreamCodec<ByteBuf, End> CODEC = StreamCodec.composite(Target.CODEC, End::target,
                ByteBufCodecs.VAR_INT, End::token, End::new);
        @Override public Type<End> type() { return TYPE; }
    }

    public record Play(Target target, int token, List<KeyboardMidi> events) implements CustomPacketPayload {
        static final Type<Play> TYPE = new Type<>(CreateMediaSystems.id("keyboard_play"));
        static final StreamCodec<ByteBuf, Play> CODEC = StreamCodec.composite(Target.CODEC, Play::target,
                ByteBufCodecs.VAR_INT, Play::token, INPUT, Play::events, Play::new);
        @Override public Type<Play> type() { return TYPE; }
    }

    public record Command(Target target, int token, int action, int first, int second, String text) implements CustomPacketPayload {
        static final Type<Command> TYPE = new Type<>(CreateMediaSystems.id("keyboard_command"));
        static final StreamCodec<ByteBuf, Command> CODEC = StreamCodec.composite(Target.CODEC, Command::target,
                ByteBufCodecs.VAR_INT, Command::token, ByteBufCodecs.VAR_INT, Command::action,
                ByteBufCodecs.VAR_INT, Command::first, ByteBufCodecs.VAR_INT, Command::second,
                ByteBufCodecs.stringUtf8(96), Command::text, Command::new);
        @Override public Type<Command> type() { return TYPE; }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Open.TYPE, Open.CODEC, (p, ctx) -> { if (client != null) client.open(p); });
        registrar.playToClient(Update.TYPE, Update.CODEC, (p, ctx) -> { if (client != null) client.update(p); });
        registrar.playToClient(End.TYPE, End.CODEC, (p, ctx) -> { if (client != null) client.end(p); });
        registrar.playToServer(Play.TYPE, Play.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                var runtime = find(player, p.target);
                if (runtime != null) runtime.play(player, p.token, p.events);
            }
        });
        registrar.playToServer(Command.TYPE, Command.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player && p.action >= 0 && p.action < Action.values().length) {
                var action = Action.values()[p.action];
                if (action == Action.WHEEL && !KeyboardBlock.looksAtKnob(player, p.target)) return;
                var runtime = find(player, p.target);
                if (runtime != null) runtime.command(player, p.token, action, p.first, p.second, p.text);
            }
        });
    }

    @Nullable private static KeyboardRuntime find(ServerPlayer player, Target target) {
        var level = player.serverLevel();
        if (target.mounted()) return MountedKeyboard.find(level, target);
        if (level.isLoaded(target.pos()) && level.getBlockEntity(target.pos()) instanceof KeyboardBlockEntity keyboard) return keyboard.runtime();
        return null;
    }
}
