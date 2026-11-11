package org.haruyq.createmediasystems.stream;

import java.util.UUID;

import org.haruyq.createmediasystems.CreateMediaSystems;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A source that plays wherever it is carried or flies (a launched disc, a PartyBox, a megaphone), on a clock of its own
 * (see PlaybackClock): its listeners' clients find it and play it themselves, and tell it its track's length as their
 * player sees it (see Length), for a track the server can't tell the length of.
 */
public interface Portable {
    /** Its clock's revision: a length told for another was for what it played before. */
    int revision();

    /** Whether it takes its track's length from {@code player}'s client. */
    boolean takesLengthFrom(ServerPlayer player);

    /** Takes {@code seconds} as its track's length while it knows none (see PlaybackClock#learn). */
    void learnLength(double seconds);

    /** An item that plays as a portable in its holder's hand (a megaphone). */
    interface Held {
        /** {@code stack}'s portable, in {@code holder}'s hand, if it is the one known by {@code id}; else null. */
        @Nullable
        Portable held(ItemStack stack, ServerPlayer holder, UUID id);
    }

    /**
     * Client → server: the portable known by {@code id} (an entity's UUID, or a held item's own id) plays a track
     * {@code seconds} long, as of {@code revision} of its clock.
     */
    record Length(UUID id, int revision, double seconds) implements CustomPacketPayload {
        public static final Type<Length> TYPE = new Type<>(CreateMediaSystems.id("portable_length"));
        public static final StreamCodec<ByteBuf, Length> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Length::id,
                ByteBufCodecs.VAR_INT, Length::revision,
                ByteBufCodecs.DOUBLE, Length::seconds,
                Length::new);

        @Override
        public Type<Length> type() {
            return TYPE;
        }

        static void register(PayloadRegistrar registrar) {
            registrar.playToServer(TYPE, CODEC, (payload, context) -> payload.tell((ServerPlayer) context.player()));
        }

        private void tell(ServerPlayer player) {
            Portable portable = player.serverLevel().getEntity(id) instanceof Portable entity ? entity : held(player);
            if (portable != null && portable.revision() == revision && portable.takesLengthFrom(player)) {
                portable.learnLength(seconds);
            }
        }

        @Nullable
        private Portable held(ServerPlayer player) {
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                Portable portable = stack.getItem() instanceof Held item ? item.held(stack, player, id) : null;
                if (portable != null) {
                    return portable;
                }
            }
            return null;
        }
    }
}
