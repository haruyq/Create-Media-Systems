package org.haruyq.createmediasystems.megaphone;

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
    public static double gain(double distance,int volume,double direction){
        double d=Math.max(0,distance)/24;return Math.clamp(volume,0,32)/32.0*.95*direction/(1+d*d);
    }
}
