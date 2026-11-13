package org.haruyq.createmediasystems.items.launcher.client;

import java.util.ArrayList;
import java.util.List;

/** Recent damage, separate from the short X pulse. Times are monotonic seconds, injected for deterministic tests. */
public final class LauncherDamageDisplay {
    public enum Mode { STACKING,FLOATING,BOTH,OFF }
    public enum Crosshair { X_WITH_SHIELD,X,OFF }
    public static final double STACK_SECONDS=1.0,FLOAT_SECONDS=.8;
    public record Entry(int target,float health,float shield,boolean head,boolean killed,boolean shieldBroken,
                        double x,double y,double z,double at) {
        public float damage(){return health+shield;}
    }
    private final ArrayList<Entry> floating=new ArrayList<>();
    private Entry latest;
    private float total;
    public void hit(Entry hit) {
        if(!Float.isFinite(hit.damage()) || hit.damage()<=0 || hit.health<0 || hit.shield<0 || !Double.isFinite(hit.at))return;
        boolean continues=latest!=null && latest.target==hit.target && hit.at>=latest.at && hit.at-latest.at<STACK_SECONDS-1e-9;
        total=continues?total+hit.damage():hit.damage();latest=hit;
        floating.removeIf(e->hit.at-e.at>=FLOAT_SECONDS);
        if(floating.size()>=12)floating.removeFirst();floating.add(hit);
    }
    public Entry latest(){return latest;}
    public float total(){return total;}
    public double age(double now){return latest==null?Double.POSITIVE_INFINITY:Math.max(0,now-latest.at);}
    public boolean visible(double now){return age(now)<STACK_SECONDS-1e-9;}
    public List<Entry> floating(double now){floating.removeIf(e->now-e.at>=FLOAT_SECONDS);return List.copyOf(floating);}
    public void reset(){floating.clear();latest=null;total=0;}
}
