package org.haruyq.createmediasystems.megaphone;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;

/** All settings require the actual held item's open server menu. */
public record MegaphoneAction(int container,int action,int value) implements CustomPacketPayload {
    public static final Type<MegaphoneAction> TYPE=new Type<>(CreateMediaSystems.id("megaphone_action"));
    public static final StreamCodec<ByteBuf,MegaphoneAction> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_INT,MegaphoneAction::container,ByteBufCodecs.VAR_INT,MegaphoneAction::action,ByteBufCodecs.VAR_INT,MegaphoneAction::value,MegaphoneAction::new);
    @Override public Type<MegaphoneAction> type(){return TYPE;}
    public static void register(PayloadRegistrar registrar){registrar.playToServer(TYPE,CODEC,(p,c)->{
        var player=c.player();if(!(player.containerMenu instanceof MegaphoneMenu menu) || menu.containerId!=p.container || !menu.stillValid(player))return;
        var item=menu.horn();switch(p.action){
            case 0->MegaphoneData.play(item,player.level(),!MegaphoneData.playing(item));
            case 1->MegaphoneData.controls(item,p.value,MegaphoneData.drive(item));
            case 2->MegaphoneData.controls(item,MegaphoneData.volume(item),p.value);
            default->{}
        }
        player.getInventory().setChanged();
    });}
}
