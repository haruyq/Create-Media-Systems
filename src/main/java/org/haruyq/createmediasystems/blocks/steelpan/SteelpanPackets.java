package org.haruyq.createmediasystems.blocks.steelpan;

import java.util.Map;
import java.util.WeakHashMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

public final class SteelpanPackets {
    public static final double HEARING_RANGE = 32;
    private static final Map<ServerPlayer, PlayerInput> INPUTS = new WeakHashMap<>();
    private static final PlayerInput LOCAL = new PlayerInput();
    private static Client client;

    private SteelpanPackets() {}

    public interface Client {
        void open(Target target);
        void strike(Strike strike);
        /** The player's own strike, played at once; the server's echo of it is skipped. */
        void played(Target target, int midi, int velocity);
    }

    public static void client(Client handler) {
        client = handler;
    }

    public record Target(int carrier, BlockPos pos) {
        static final StreamCodec<ByteBuf, Target> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Target::carrier, BlockPos.STREAM_CODEC, Target::pos, Target::new);

        public Target {
            pos = pos.immutable();
        }

        public static Target placed(BlockPos pos) {
            return new Target(-1, pos);
        }

        public boolean mounted() {
            return carrier >= 0;
        }

        @Nullable
        public Vec3 origin(Level level, float partial) {
            if (mounted()) return MountedSteelpan.origin(level, this, partial);
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof SteelpanBlock.Entity pan)
                    || pan.isRemoved()) return null;
            return project(level, pos, soundPosition(pos));
        }

        static Vec3 soundPosition(BlockPos pos) {
            return Vec3.atBottomCenterOf(pos).add(0, SteelpanLayout.DATA.rim_height() - .1, 0);
        }

        static Vec3 project(Level level, BlockPos anchor, Vec3 local) {
            return level.isClientSide ? SableCompat.toRenderWorld(level, anchor, local)
                    : SableCompat.toWorld(level, anchor, local);
        }
    }

    private record Open(Target target) implements CustomPacketPayload {
        private static final Type<Open> TYPE = new Type<>(CreateMediaSystems.id("steelpan_open"));
        private static final StreamCodec<ByteBuf, Open> CODEC = Target.CODEC.map(Open::new, Open::target);

        @Override public Type<Open> type() { return TYPE; }
    }

    public record Play(Target target, int midi, int velocity) implements CustomPacketPayload {
        private static final Type<Play> TYPE = new Type<>(CreateMediaSystems.id("steelpan_play"));
        private static final StreamCodec<ByteBuf, Play> CODEC = StreamCodec.composite(
                Target.CODEC, Play::target, ByteBufCodecs.VAR_INT, Play::midi, ByteBufCodecs.VAR_INT, Play::velocity, Play::new);

        @Override public Type<Play> type() { return TYPE; }
    }

    public record Strike(Target target, int midi, int velocity, boolean left) implements CustomPacketPayload {
        private static final Type<Strike> TYPE = new Type<>(CreateMediaSystems.id("steelpan_strike"));
        static final StreamCodec<ByteBuf, Strike> CODEC = StreamCodec.composite(
                Target.CODEC, Strike::target, ByteBufCodecs.VAR_INT, Strike::midi,
                ByteBufCodecs.VAR_INT, Strike::velocity, ByteBufCodecs.BOOL, Strike::left, Strike::new);

        @Override public Type<Strike> type() { return TYPE; }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Open.TYPE, Open.CODEC, (packet, context) -> {
            if (client != null) client.open(packet.target());
        });
        registrar.playToClient(Strike.TYPE, Strike.CODEC, (packet, context) -> {
            if (client != null && valid(packet.midi(), packet.velocity())) client.strike(packet);
        });
        registrar.playToServer(Play.TYPE, Play.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) play(player, packet);
        });
    }

    static void open(ServerPlayer player, Target target) {
        if (!canPlay(player, target)) return;
        var input = INPUTS.computeIfAbsent(player, ignored -> new PlayerInput());
        input.target = target;
        input.level = player.serverLevel();
        PacketDistributor.sendToPlayer(player, new Open(target));
    }

    private static void play(ServerPlayer player, Play packet) {
        if (!valid(packet.midi(), packet.velocity())) return;
        var target = packet.target();
        var input = INPUTS.get(player);
        if (input == null || input.level != player.serverLevel() || !target.equals(input.target)
                || !canPlay(player, target) || !input.take(player.tickCount)) return;
        if (target.mounted()) {
            MountedSteelpan.strike(player.serverLevel(), target, packet.midi(), packet.velocity());
        } else if (player.level().getBlockEntity(target.pos()) instanceof SteelpanBlock.Entity pan) {
            pan.strike(packet.midi(), packet.velocity());
        }
    }

    /** Client side: plays the screen's strike at once when the server would accept it too. */
    public static void playLocally(Player player, Target target, int midi, int velocity) {
        if (valid(midi, velocity) && canPlay(player, target) && LOCAL.take(player.tickCount)) played(target, midi, velocity);
    }

    static void played(Target target, int midi, int velocity) {
        if (client != null) client.played(target, midi, velocity);
    }

    public static boolean canPlay(Player player, Target target) {
        Vec3 origin = target.origin(player.level(), 1);
        return player.isAlive() && !player.isSpectator() && origin != null
                && SableCompat.eye(player, 1).distanceToSqr(origin) <= Reach.HAND * Reach.HAND
                && player.level().mayInteract(player, BlockPos.containing(origin));
    }

    static boolean valid(int midi, int velocity) {
        return SteelpanLayout.contains(midi) && velocity >= 1 && velocity <= 127;
    }

    static void broadcast(ServerLevel level, Strike strike) {
        Vec3 origin = strike.target().origin(level, 1);
        if (origin == null) return;
        for (var player : level.players()) {
            if (SableCompat.eye(player, 1).distanceToSqr(origin) <= HEARING_RANGE * HEARING_RANGE) {
                PacketDistributor.sendToPlayer(player, strike);
            }
        }
    }

    private static final class PlayerInput {
        private static final int BURST = 32;
        Target target;
        ServerLevel level;
        int lastTick;
        int remaining = BURST;

        boolean take(int tick) {
            remaining = Math.min(BURST, remaining + 3 * Math.clamp(tick - lastTick, 0, BURST));
            lastTick = tick;
            if (remaining == 0) return false;
            remaining--;
            return true;
        }
    }
}
