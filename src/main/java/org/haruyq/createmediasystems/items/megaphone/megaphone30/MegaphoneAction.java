package org.haruyq.createmediasystems.items.megaphone.megaphone30;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.stream.Remotable.Action;

/** All settings require the actual held item's open server menu. */
public record MegaphoneAction(int container,Kind kind,int value) implements CustomPacketPayload {
    /** What is asked of the horn, with its stable wire id. */
    public enum Kind {
        PLAY_PAUSE(0), VOLUME(1), DRIVE(2), PREVIOUS(3), NEXT(4), SEEK(5), SEEK_BY(6);

        public final int id;

        Kind(int id) { this.id = id; }

        public static Kind byId(int id) {
            for (Kind kind : values()) if (kind.id == id) return kind;
            return null;
        }
    }

    public static final Type<MegaphoneAction> TYPE=new Type<>(CreateMediaSystems.id("megaphone_action"));
    private static final StreamCodec<ByteBuf,Kind> KIND=ByteBufCodecs.VAR_INT.map(Kind::byId,kind->kind.id);
    public static final StreamCodec<ByteBuf,MegaphoneAction> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_INT,MegaphoneAction::container,KIND,MegaphoneAction::kind,ByteBufCodecs.VAR_INT,MegaphoneAction::value,MegaphoneAction::new);
    @Override public Type<MegaphoneAction> type(){return TYPE;}
    public static void register(PayloadRegistrar registrar){registrar.playToServer(TYPE,CODEC,(p,c)->{
        var player=c.player();if(p.kind==null || !(player.containerMenu instanceof MegaphoneMenu menu) || menu.containerId!=p.container || !menu.stillValid(player))return;
        var item=menu.horn();switch(p.kind){
            case PLAY_PAUSE->MegaphoneData.control(item,player.level(),Action.PLAY_PAUSE,0);
            case PREVIOUS->MegaphoneData.control(item,player.level(),Action.PREVIOUS,0);
            case NEXT->MegaphoneData.control(item,player.level(),Action.NEXT,0);
            case SEEK->MegaphoneData.control(item,player.level(),Action.SEEK,p.value);
            case SEEK_BY->MegaphoneData.control(item,player.level(),Action.SEEK_BY,p.value);
            case VOLUME->{MegaphoneData.tick(item,player.level());MegaphoneData.controls(item,p.value,MegaphoneData.drive(item));}
            case DRIVE->MegaphoneData.controls(item,MegaphoneData.volume(item),p.value);
        }
        player.getInventory().setChanged();
    });}
}
