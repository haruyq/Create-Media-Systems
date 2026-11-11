package org.haruyq.createmediasystems.launcher;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.launcher.client.LauncherClient;

/** The server's confirmations of a shot and a hit, for the shooter's crosshair, recoil and damage numbers. */
public final class LauncherFeedback {
    private LauncherFeedback() {}
    public record Shot(long tick) implements CustomPacketPayload {
        public static final Type<Shot> TYPE=new Type<>(CreateMediaSystems.id("launcher_shot"));
        public static final StreamCodec<ByteBuf,Shot> CODEC=StreamCodec.of((b,p)->b.writeLong(p.tick),b->new Shot(b.readLong()));
        @Override public Type<Shot> type(){return TYPE;}
    }
    public record Hit(int target,boolean headshot,boolean killed,float health,float shield,boolean shieldBroken,double x,double y,double z) implements CustomPacketPayload {
        public float damage(){return health+shield;}
        public static final Type<Hit> TYPE=new Type<>(CreateMediaSystems.id("launcher_hit"));
        public static final StreamCodec<ByteBuf,Hit> CODEC=StreamCodec.of(
                (b,p)->{b.writeInt(p.target);b.writeBoolean(p.headshot);b.writeBoolean(p.killed);b.writeFloat(p.health);b.writeFloat(p.shield);b.writeBoolean(p.shieldBroken);b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);},
                b->new Hit(b.readInt(),b.readBoolean(),b.readBoolean(),b.readFloat(),b.readFloat(),b.readBoolean(),b.readDouble(),b.readDouble(),b.readDouble()));
        @Override public Type<Hit> type(){return TYPE;}
    }
    public record Lock(int target, int part) implements CustomPacketPayload {
        public static final Lock NONE = new Lock(-1, -1);
        public static final Type<Lock> TYPE = new Type<>(CreateMediaSystems.id("launcher_lock"));
        public static final StreamCodec<ByteBuf, Lock> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Lock::target, ByteBufCodecs.VAR_INT, Lock::part, Lock::new);

        @Override public Type<Lock> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar r) {
        r.playToClient(Shot.TYPE,Shot.CODEC,(p,c)->LauncherClient.shot(p));
        r.playToClient(Hit.TYPE,Hit.CODEC,(p,c)->LauncherClient.hit(p));
        r.playToClient(Lock.TYPE,Lock.CODEC,(p,c)->LauncherClient.lock(p));
    }
}
