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
public record PartyBoxAction(int entity, Kind kind, int value, int revision) implements CustomPacketPayload {
    /** What is asked of the box, with its stable wire id. All but LENGTH come from its open menu. */
    public enum Kind {
        PLAY_PAUSE(0), VOLUME(1), BASS(2), LIGHTS(3), PULL(4), PACK(5),
        /** A listener's client tells its track's length in seconds, for a track the server can't tell the length of. */
        LENGTH(7);

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
            ByteBufCodecs.VAR_INT,PartyBoxAction::value,ByteBufCodecs.VAR_INT,PartyBoxAction::revision,PartyBoxAction::new);
    @Override public Type<PartyBoxAction> type() { return TYPE; }
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE,CODEC,(p,context)->handle((ServerPlayer)context.player(),p));
    }
    public static void handle(ServerPlayer player, PartyBoxAction request) {
        if(request.kind==null || !(player.level().getEntity(request.entity) instanceof PartyBoxEntity box) || box.isRemoved()) return;
        if(request.kind==Kind.LENGTH) {
            if(player.distanceToSqr(box)<128*128 && request.revision==box.revision() && MediaItem.plausibleLength(request.value))
                box.noteDuration(request.value);
            return;
        }
        if(!(player.containerMenu instanceof PartyBoxMenu menu) || menu.box!=box || !menu.stillValid(player)) return;
        switch(request.kind) {
            case PLAY_PAUSE -> { if(box.playing()) box.pause(); else box.play(); }
            case VOLUME -> box.setVolume(request.value);
            case BASS -> box.setBass(request.value);
            case LIGHTS -> box.setLights(request.value);
            case PULL -> { box.togglePull(player); player.closeContainer(); }
            case PACK -> { if(box.pack(player)) player.closeContainer(); }
            case LENGTH -> {}
        }
    }
}
