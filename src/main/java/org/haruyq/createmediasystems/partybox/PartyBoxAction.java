package org.haruyq.createmediasystems.partybox;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.MediaItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** IDs/values are requests; proximity, menu ownership and current revision are checked on the server. */
public record PartyBoxAction(int entity, int action, int value, int revision) implements CustomPacketPayload {
    public static final Type<PartyBoxAction> TYPE=new Type<>(CreateMediaSystems.id("partybox_action"));
    public static final StreamCodec<ByteBuf,PartyBoxAction> CODEC=StreamCodec.composite(
            ByteBufCodecs.VAR_INT,PartyBoxAction::entity,ByteBufCodecs.VAR_INT,PartyBoxAction::action,
            ByteBufCodecs.VAR_INT,PartyBoxAction::value,ByteBufCodecs.VAR_INT,PartyBoxAction::revision,PartyBoxAction::new);
    @Override public Type<PartyBoxAction> type() { return TYPE; }
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE,CODEC,(p,context)->handle((ServerPlayer)context.player(),p));
    }
    public static void handle(ServerPlayer player, PartyBoxAction request) {
        if(!(player.level().getEntity(request.entity) instanceof PartyBoxEntity box) || box.isRemoved()) return;
        if(request.action==7) {
            if(player.distanceToSqr(box)<128*128 && request.revision==box.revision() && MediaItem.plausibleLength(request.value))
                box.noteDuration(request.value);
            return;
        }
        if(!(player.containerMenu instanceof PartyBoxMenu menu) || menu.box!=box || !menu.stillValid(player)) return;
        switch(request.action) {
            case 0 -> { if(box.playing()) box.pause(); else box.play(); }
            case 1 -> box.setVolume(request.value);
            case 2 -> box.setBass(request.value);
            case 3 -> box.setLights(request.value);
            case 4 -> { box.togglePull(player); player.closeContainer(); }
            case 5 -> { if(box.pack(player)) player.closeContainer(); }
            case 6 -> { box.pause(); /* Stop keeps the CD and server playback position. */ }
            default -> {}
        }
    }
}
