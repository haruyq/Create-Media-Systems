package org.haruyq.createmediasystems.launcher;

import org.haruyq.createmediasystems.CreateMediaSystems;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** When an origin has no server metadata, only the shooter's decoder may supply its finite track length. */
public record DiscPlaybackMetadata(int entityId, double seconds) implements CustomPacketPayload {
    public static final Type<DiscPlaybackMetadata> TYPE = new Type<>(CreateMediaSystems.id("disc_playback_metadata"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DiscPlaybackMetadata> CODEC = StreamCodec.of(
            (buf, data) -> { buf.writeVarInt(data.entityId); buf.writeDouble(data.seconds); },
            buf -> new DiscPlaybackMetadata(buf.readVarInt(), buf.readDouble()));
    @Override public Type<DiscPlaybackMetadata> type() { return TYPE; }
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, (payload, context) -> {
            var player = (ServerPlayer) context.player();
            if (player.serverLevel().getEntity(payload.entityId) instanceof DiscProjectile disc
                    && disc.getOwner() == player) disc.noteDuration(payload.seconds);
        });
    }
}
