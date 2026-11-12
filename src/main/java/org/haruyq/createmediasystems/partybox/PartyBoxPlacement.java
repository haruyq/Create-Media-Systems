package org.haruyq.createmediasystems.partybox;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.ContraptionSurface;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.jetbrains.annotations.Nullable;

/** Place the held trolley on the clicked face of an already assembled contraption. */
public record PartyBoxPlacement(int carrier, boolean offhand) implements CustomPacketPayload {
    private static final Type<PartyBoxPlacement> TYPE = new Type<>(CreateMediaSystems.id("partybox_place_on_contraption"));
    private static final StreamCodec<ByteBuf, PartyBoxPlacement> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PartyBoxPlacement::carrier, ByteBufCodecs.BOOL, PartyBoxPlacement::offhand, PartyBoxPlacement::new);

    @Override
    public Type<PartyBoxPlacement> type() {
        return TYPE;
    }

    @Nullable
    public static ContraptionSurface.Hit pointedAt(Player player) {
        Vec3 eye = SableCompat.eye(player, 1);
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        var block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        var hit = ContraptionSurface.nearest(player, eye, SableCompat.hitInWorld(player.level(), block));
        return hit != null && hit.outside(1).y - hit.location().y > .5 ? hit : null;
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, (packet, context) -> packet.place(context.player()));
    }

    InteractionResult place(Player player) {
        if (player.isSpectator() || !player.isAlive()) return InteractionResult.FAIL;
        var item = player.getItemInHand(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (!item.is(PartyBoxFeature.PARTYBOX.get())) return InteractionResult.FAIL;
        var hit = pointedAt(player);
        if (hit == null || hit.carrier().getId() != carrier
                || !SableCompat.mayInteract(player.level(), player, BlockPos.containing(hit.location()))) {
            return InteractionResult.FAIL;
        }
        return PartyBoxItem.place(player.level(), player, item, hit.outside(.02));
    }
}
