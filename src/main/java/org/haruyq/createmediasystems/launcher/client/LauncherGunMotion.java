package org.haruyq.createmediasystems.launcher.client;

/** Tick-based critically damped springs. Rendering only interpolates; FPS never changes recoil or ADS time. */
public final class LauncherGunMotion {
    private float aim,oldAim,aimVelocity,kick,oldKick,kickVelocity;
    public void tick(boolean aiming) {
        oldAim=aim;oldKick=kick;
        float target=aiming?1:0,w=24,dt=.05f;
        float relative=aim-target,term=aimVelocity+w*relative,e=(float)Math.exp(-w*dt);
        aim=target+(relative+term*dt)*e;aimVelocity=(aimVelocity-term*w*dt)*e;
        w=22;term=kickVelocity+w*kick;e=(float)Math.exp(-w*dt);
        kick=(kick+term*dt)*e;kickVelocity=(kickVelocity-term*w*dt)*e;
    }
    public void shot(){kickVelocity=Math.min(90,kickVelocity+52);}
    public float aim(float partial){return Math.clamp(oldAim+(aim-oldAim)*partial,0,1);}
    public float kick(float partial){return Math.clamp(oldKick+(kick-oldKick)*partial,0,2);}
    public void reset(){aim=oldAim=aimVelocity=kick=oldKick=kickVelocity=0;}
}
