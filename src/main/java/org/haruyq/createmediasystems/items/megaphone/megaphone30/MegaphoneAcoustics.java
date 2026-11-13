package org.haruyq.createmediasystems.items.megaphone.megaphone30;

import org.haruyq.createmediasystems.playback.MusicEmitter;

import net.minecraft.world.phys.Vec3;

/** A forward horn beam, with a quiet but audible rear lobe. Distances are Minecraft adaptations. */
public final class MegaphoneAcoustics {
    public static final double RANGE=112;
    private MegaphoneAcoustics() {}
    public static double direction(Vec3 forward,Vec3 toListener) {
        if(toListener.lengthSqr()<.16)return 1;
        double cosine=forward.normalize().dot(toListener.normalize());
        double front=Math.pow(Math.max(0,(cosine+.15)/1.15),3);
        return .12+.88*front;
    }
    /** FE a playing horn spends per tick at {@code volume} (0..32). */
    public static double drainPerTick(int volume){double level=Math.clamp(volume,0,32)/32.0;return 2+6*level*level;}
    public static double gain(double distance,int volume,double direction){
        return Math.clamp(volume,0,32)/32.0*.95*direction*MusicEmitter.falloff(distance,24);
    }
}
