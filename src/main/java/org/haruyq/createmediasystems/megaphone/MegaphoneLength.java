package org.haruyq.createmediasystems.megaphone;

import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client → server: the holder's own client tells the length (seconds) of the track its megaphone {@code id} plays, at
 * {@code revision} of its clock, for a track the server can't tell the length of. Only for a horn in the sender's hand.
 */
public record MegaphoneLength(UUID id, int revision, double seconds) implements CustomPacketPayload {
    public static final Type<MegaphoneLength> TYPE = new Type<>(CreateMediaSystems.id("megaphone_length"));
    public static final StreamCodec<ByteBuf, MegaphoneLength> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, MegaphoneLength::id,
            ByteBufCodecs.VAR_INT, MegaphoneLength::revision,
            ByteBufCodecs.DOUBLE, MegaphoneLength::seconds,
            MegaphoneLength::new);

    @Override
    public Type<MegaphoneLength> type() {
        return TYPE;
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, (payload, context) -> {
            for (InteractionHand hand : InteractionHand.values()) {
                var item = context.player().getItemInHand(hand);
                if (item.getItem() instanceof MegaphoneItem && MegaphoneData.id(item).filter(payload.id::equals).isPresent()
                        && MegaphoneData.revision(item) == payload.revision) {
                    MegaphoneData.noteDuration(item, payload.seconds);
                }
            }
        });
    }
}
