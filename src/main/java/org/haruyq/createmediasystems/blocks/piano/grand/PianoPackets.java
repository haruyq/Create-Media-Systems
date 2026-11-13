package org.haruyq.createmediasystems.blocks.piano.grand;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.jetbrains.annotations.Nullable;

public final class PianoPackets {
    public static final int BATCH_LIMIT = 96;
    private static final StreamCodec<ByteBuf, List<PianoEvent>> EVENTS = PianoEvent.CODEC.apply(ByteBufCodecs.list(BATCH_LIMIT));
    private static final StreamCodec<ByteBuf, Vec3> POSITION = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    private static Client client;

    private PianoPackets() {}

    public interface Client {
        void open(Open packet);
        void update(Update packet);
        void end(End packet);
    }

    public static void client(Client handler) { client = handler; }

    public record Target(int carrier, BlockPos pos) {
        public Target {
            pos = pos.immutable();
        }

        public static final StreamCodec<ByteBuf, Target> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Target::carrier, BlockPos.STREAM_CODEC, Target::pos, Target::new);

        public static Target placed(BlockPos pos) { return new Target(-1, pos); }
        public boolean mounted() { return carrier >= 0; }
    }

    public record Frame(Vec3 origin, boolean lidOpen) {
        static final StreamCodec<ByteBuf, Frame> CODEC = StreamCodec.composite(
                POSITION, Frame::origin, ByteBufCodecs.BOOL, Frame::lidOpen, Frame::new);
    }

    public record Open(Target target, int session, Frame frame) implements CustomPacketPayload {
        static final Type<Open> TYPE = new Type<>(CreateMediaSystems.id("piano_open"));
        static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(Target.CODEC, Open::target,
                ByteBufCodecs.VAR_INT, Open::session, Frame.CODEC, Open::frame, Open::new);
        @Override public Type<Open> type() { return TYPE; }
    }

    public record Play(Target target, int session, List<PianoEvent> events) implements CustomPacketPayload {
        static final Type<Play> TYPE = new Type<>(CreateMediaSystems.id("piano_play"));
        static final StreamCodec<ByteBuf, Play> CODEC = StreamCodec.composite(Target.CODEC, Play::target,
                ByteBufCodecs.VAR_INT, Play::session, EVENTS, Play::events, Play::new);
        @Override public Type<Play> type() { return TYPE; }
    }

    public record Update(Target target, int session, Frame frame, boolean snapshot, List<PianoEvent> events)
            implements CustomPacketPayload {
        static final Type<Update> TYPE = new Type<>(CreateMediaSystems.id("piano_update"));
        static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(Target.CODEC, Update::target,
                ByteBufCodecs.VAR_INT, Update::session, Frame.CODEC, Update::frame, ByteBufCodecs.BOOL, Update::snapshot,
                EVENTS, Update::events, Update::new);
        @Override public Type<Update> type() { return TYPE; }
    }

    public record Command(Target target, int session, boolean toggleLid) implements CustomPacketPayload {
        static final Type<Command> TYPE = new Type<>(CreateMediaSystems.id("piano_command"));
        static final StreamCodec<ByteBuf, Command> CODEC = StreamCodec.composite(Target.CODEC, Command::target,
                ByteBufCodecs.VAR_INT, Command::session, ByteBufCodecs.BOOL, Command::toggleLid, Command::new);
        @Override public Type<Command> type() { return TYPE; }
    }

    public record End(Target target, int session) implements CustomPacketPayload {
        static final Type<End> TYPE = new Type<>(CreateMediaSystems.id("piano_end"));
        static final StreamCodec<ByteBuf, End> CODEC = StreamCodec.composite(Target.CODEC, End::target,
                ByteBufCodecs.VAR_INT, End::session, End::new);
        @Override public Type<End> type() { return TYPE; }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Open.TYPE, Open.CODEC, (packet, context) -> { if (client != null) client.open(packet); });
        registrar.playToClient(Update.TYPE, Update.CODEC, (packet, context) -> { if (client != null) client.update(packet); });
        registrar.playToClient(End.TYPE, End.CODEC, (packet, context) -> { if (client != null) client.end(packet); });
        registrar.playToServer(Play.TYPE, Play.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                var piano = find(player, packet.target);
                if (piano != null) piano.play(player, packet.session, packet.events);
            }
        });
        registrar.playToServer(Command.TYPE, Command.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                var piano = find(player, packet.target);
                if (piano != null) piano.command(player, packet.session, packet.toggleLid);
            }
        });
    }

    @Nullable
    private static PianoSession find(ServerPlayer player, Target target) {
        var level = player.serverLevel();
        if (target.mounted()) return MountedPiano.session(level, target);
        if (level.isLoaded(target.pos()) && level.getBlockEntity(target.pos()) instanceof PianoBlockEntity piano) {
            return piano.session();
        }
        return null;
    }
}
