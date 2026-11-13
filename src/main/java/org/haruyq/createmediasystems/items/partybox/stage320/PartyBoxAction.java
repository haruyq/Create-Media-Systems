package org.haruyq.createmediasystems.items.partybox.stage320;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.Remotable.Action;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** IDs/values are requests; proximity and menu ownership are checked on the server. */
public record PartyBoxAction(int entity, Kind kind, int value) implements CustomPacketPayload {
    /** What is asked of the box from its open menu, with its stable wire id. */
    public enum Kind {
        PLAY_PAUSE(0), VOLUME(1), BASS(2), LIGHTS(3), PULL(4), PACK(5),
        PREVIOUS(6), NEXT(7), SEEK(8), SEEK_BY(9);

        public final int id;

        Kind(int id) { this.id = id; }

        public static Kind byId(int id) {
            for (Kind kind : values()) if (kind.id == id) return kind;
            return null;
        }
    }

    public static final Type<PartyBoxAction> TYPE=new Type<>(CreateMediaSystems.id("partybox_action"));
    private static final StreamCodec<ByteBuf,Kind> KIND=ByteBufCodecs.VAR_INT.map(Kind::byId,kind->kind.id);
    public static final StreamCodec<ByteBuf,PartyBoxAction> CODEC=StreamCodec.composite(
            ByteBufCodecs.VAR_INT,PartyBoxAction::entity,KIND,PartyBoxAction::kind,
            ByteBufCodecs.VAR_INT,PartyBoxAction::value,PartyBoxAction::new);
    @Override public Type<PartyBoxAction> type() { return TYPE; }
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE,CODEC,(p,context)->handle((ServerPlayer)context.player(),p));
    }
    public static void handle(ServerPlayer player, PartyBoxAction request) {
        if(request.kind==null || !(player.level().getEntity(request.entity) instanceof PartyBoxEntity box) || box.isRemoved()) return;
        if(!(player.containerMenu instanceof PartyBoxMenu menu) || menu.box()!=box || !menu.stillValid(player)) return;
        switch(request.kind) {
            case PLAY_PAUSE -> box.control(Action.PLAY_PAUSE, 0);
            case VOLUME -> { box.settlePower(); box.setVolume(request.value); }
            case BASS -> { box.settlePower(); box.setBass(request.value); }
            case LIGHTS -> { box.settlePower(); box.setLights(request.value); }
            case PREVIOUS -> box.control(Action.PREVIOUS, 0);
            case NEXT -> box.control(Action.NEXT, 0);
            case SEEK -> box.control(Action.SEEK, request.value);
            case SEEK_BY -> box.control(Action.SEEK_BY, request.value);
            case PULL -> { box.togglePull(player); player.closeContainer(); }
            case PACK -> { if(box.pack(player)) player.closeContainer(); }
        }
    }
}
