package org.haruyq.createmediasystems.launcher;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;

/** The server meters automatic fire; input packets cannot increase fire rate or invent ammunition. */
@EventBusSubscriber(modid=CreateMediaSystems.MOD_ID)
public final class DiscLauncherTrigger {
    private record Held(ItemStack weapon,long updated,float yaw,float pitch) {}
    private static final Map<UUID,Held> HELD=new HashMap<>();
    private DiscLauncherTrigger() {}
    public record Input(boolean pressed,float yaw,float pitch) implements CustomPacketPayload {
        public static final Type<Input> TYPE=new Type<>(CreateMediaSystems.id("launcher_trigger"));
        public static final StreamCodec<ByteBuf,Input> CODEC=StreamCodec.of(
                (b,p)->{b.writeBoolean(p.pressed);b.writeFloat(p.yaw);b.writeFloat(p.pitch);},
                b->new Input(b.readBoolean(),b.readFloat(),b.readFloat()));
        @Override public Type<Input> type(){return TYPE;}
    }
    public static void input(ServerPlayer p,Input input) {
        if(!input.pressed || !Float.isFinite(input.yaw) || !Float.isFinite(input.pitch)
                || Math.abs(input.pitch)>90 || !p.isAlive() || p.isSpectator()
                || !(p.getMainHandItem().getItem() instanceof DiscLauncherItem)) {
            HELD.remove(p.getUUID());return;
        }
        HELD.put(p.getUUID(),new Held(p.getMainHandItem(),p.level().getGameTime(),input.yaw,input.pitch));
        DiscLauncherItem.fire(p,input.yaw,input.pitch);
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event) {
        if(!(event.getEntity() instanceof ServerPlayer p))return;
        var held=HELD.get(p.getUUID());if(held==null)return;
        if(p.level().getGameTime()-held.updated>6 || !p.isAlive() || p.isSpectator() || p.getMainHandItem()!=held.weapon) {
            HELD.remove(p.getUUID());return;
        }
        DiscLauncherItem.fire(p,held.yaw,held.pitch);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){HELD.remove(event.getEntity().getUUID());}
    public static void register(PayloadRegistrar r){r.playToServer(Input.TYPE,Input.CODEC,(p,c)->input((ServerPlayer)c.player(),p));}
}
