package org.haruyq.createmediasystems.blocks.stagecustom;

import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.jetbrains.annotations.Nullable;

public final class StageCustomMidi {
    private static final int HEARING_RANGE = 16;
    private static final Map<ServerPlayer, InputRate> INPUTS = new WeakHashMap<>();
    private static Consumer<Hit> playback = hit -> {};

    private StageCustomMidi() {}

    @Nullable
    public static Drum drum(int note) {
        return switch (note) {
            case 35, 36 -> Drum.BASS;
            case 48, 50 -> Drum.TOM_10;
            case 45, 47 -> Drum.TOM_12;
            case 41, 43 -> Drum.FLOOR;
            case 37, 38, 40 -> Drum.SNARE;
            default -> null;
        };
    }

    public static int note(Drum drum) {
        return switch (drum) {
            case BASS -> 36;
            case TOM_10 -> 48;
            case TOM_12 -> 45;
            case FLOOR -> 43;
            case SNARE -> 38;
        };
    }

    @Nullable
    public static Voice voice(int note) {
        return switch (note) {
            case 42 -> Voice.HAT_CLOSED;
            case 44 -> Voice.HAT_PEDAL;
            case 46 -> Voice.HAT_OPEN;
            case 49, 55, 57 -> Voice.CRASH;
            case 51, 59 -> Voice.RIDE_BOW;
            case 53 -> Voice.RIDE_BELL;
            default -> null;
        };
    }

    public static int note(Voice voice) {
        return switch (voice) {
            case HAT_CLOSED -> 42;
            case HAT_PEDAL -> 44;
            case HAT_OPEN -> 46;
            case CRASH -> 49;
            case RIDE_BOW -> 51;
            case RIDE_BELL -> 53;
        };
    }

    public static Vec3 origin(StageCustomBlock.Entity kit) {
        return Vec3.atBottomCenterOf(kit.getBlockPos()).add(0, .52 * StageCustomBlock.scale(kit.getBlockState()), 0);
    }

    public static void playback(Consumer<Hit> handler) {
        playback = handler;
    }

    @Nullable
    public static StageCustomBlock.Entity reachable(Player player, BlockPos root) {
        if (!player.isAlive() || player.isSpectator()) return null;
        var kit = Reach.blockEntity(player, root, Reach.HAND, StageCustomBlock.Entity.class);
        return kit != null && !kit.isRemoved() && SableCompat.mayInteract(player.level(), player, root) ? kit : null;
    }

    public record Hit(BlockPos root, int note, int velocity) implements CustomPacketPayload {
        private static final Type<Hit> TYPE = new Type<>(CreateMediaSystems.id("stage_custom_midi"));
        private static final StreamCodec<ByteBuf, Hit> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Hit::root, ByteBufCodecs.VAR_INT, Hit::note,
                ByteBufCodecs.VAR_INT, Hit::velocity, Hit::new);

        @Override
        public Type<Hit> type() { return TYPE; }
    }

    record Played(Hit hit) implements CustomPacketPayload {
        private static final Type<Played> TYPE = new Type<>(CreateMediaSystems.id("stage_custom_played"));
        private static final StreamCodec<ByteBuf, Played> CODEC = Hit.CODEC.map(Played::new, Played::hit);

        @Override
        public Type<Played> type() { return TYPE; }
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Hit.TYPE, Hit.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) strike(player, packet);
        });
        registrar.playToClient(Played.TYPE, Played.CODEC, (packet, context) -> {
            if (valid(packet.hit())) playback.accept(packet.hit());
        });
    }

    static void broadcast(StageCustomBlock.Entity kit, int note, int velocity) {
        if (!(kit.getLevel() instanceof ServerLevel level)) return;
        var position = SableCompat.toWorld(level, kit.getBlockPos(), origin(kit));
        var played = new Played(new Hit(kit.getBlockPos(), note, velocity));
        for (var player : level.players()) {
            if (SableCompat.eye(player, 1).distanceToSqr(position) <= HEARING_RANGE * HEARING_RANGE) {
                PacketDistributor.sendToPlayer(player, played);
            }
        }
    }

    private static boolean valid(Hit hit) {
        return (drum(hit.note()) != null || voice(hit.note()) != null) && hit.velocity() >= 1 && hit.velocity() <= 127;
    }

    static boolean strike(ServerPlayer player, Hit hit) {
        if (!valid(hit)) return false;
        var kit = reachable(player, hit.root());
        Voice cymbal = voice(hit.note());
        if (kit == null || (cymbal != null && !StageCustomBlock.isComplete(kit.getBlockState()))
                || !INPUTS.computeIfAbsent(player, ignored -> new InputRate()).take(player.tickCount)) return false;
        if (cymbal == null) {
            kit.strike(drum(hit.note()), 0, 0, hit.velocity());
        } else if (cymbal == Voice.HAT_PEDAL) {
            kit.closeHat(hit.velocity());
        } else {
            if (cymbal == Voice.HAT_CLOSED || cymbal == Voice.HAT_OPEN) kit.setHatOpen(cymbal == Voice.HAT_OPEN);
            float radialHit = cymbal == Voice.RIDE_BELL ? 0 : .65f;
            kit.strikeCymbal(new StageCustomCymbals.Hit(cymbal.cymbal, 0, radialHit, 0, cymbal), hit.velocity());
        }
        return true;
    }

    private static final class InputRate {
        private static final int BURST = 32;
        private int tokens = BURST;
        private int tick;

        boolean take(int now) {
            tokens = Math.min(BURST, tokens + 3 * Math.clamp(now - tick, 0, BURST));
            tick = now;
            if (tokens == 0) return false;
            tokens--;
            return true;
        }
    }
}
