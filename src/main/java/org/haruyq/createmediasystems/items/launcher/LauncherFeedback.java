package org.haruyq.createmediasystems.items.launcher;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.items.launcher.client.LauncherClient;

/** The server's shot, hit and aim-lock updates for the shooter. */
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
    public record Lock(int target, int part, Vec3 point) implements CustomPacketPayload {
        public static final Lock NONE = new Lock(-1, -1, Vec3.ZERO);
        public static final Type<Lock> TYPE = new Type<>(CreateMediaSystems.id("launcher_lock"));
        private static final StreamCodec<ByteBuf, Vec3> POINT_CODEC = StreamCodec.of((buf, point) -> {
            buf.writeDouble(point.x);
            buf.writeDouble(point.y);
            buf.writeDouble(point.z);
        }, buf -> new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        public static final StreamCodec<ByteBuf, Lock> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Lock::target, ByteBufCodecs.VAR_INT, Lock::part,
                POINT_CODEC, Lock::point, Lock::new);

        @Override public Type<Lock> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar r) {
        r.playToClient(Shot.TYPE,Shot.CODEC,(p,c)->LauncherClient.shot(p));
        r.playToClient(Hit.TYPE,Hit.CODEC,(p,c)->LauncherClient.hit(p));
        r.playToClient(Lock.TYPE,Lock.CODEC,(p,c)->LauncherClient.lock(p));
    }
}
