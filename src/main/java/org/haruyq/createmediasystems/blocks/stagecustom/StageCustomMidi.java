package org.haruyq.createmediasystems.blocks.stagecustom;

import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.jetbrains.annotations.Nullable;

public final class StageCustomMidi {
    private static final Map<ServerPlayer, InputRate> INPUTS = new WeakHashMap<>();

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

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Hit.TYPE, Hit.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) strike(player, packet);
        });
    }

    static boolean strike(ServerPlayer player, Hit hit) {
        Drum drum = drum(hit.note());
        if (drum == null || hit.velocity() < 1 || hit.velocity() > 127) return false;
        var kit = reachable(player, hit.root());
        if (kit == null || !INPUTS.computeIfAbsent(player, ignored -> new InputRate()).take(player.tickCount)) return false;
        kit.strike(drum, 0, 0, hit.velocity());
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
