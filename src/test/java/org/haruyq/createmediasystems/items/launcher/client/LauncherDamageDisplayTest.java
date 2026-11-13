package org.haruyq.createmediasystems.items.launcher.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LauncherDamageDisplayTest {
    private static LauncherDamageDisplay.Entry hit(int id,float damage,double at){return new LauncherDamageDisplay.Entry(id,damage,0,false,false,false,0,0,0,at);}
    @Test void recentHitsDisplay40Then80Then120InsteadOfReplacingEachOther() {
        var d=new LauncherDamageDisplay();
        d.hit(hit(7,40,0));assertEquals(40,d.total());d.hit(hit(7,40,.2));assertEquals(80,d.total());
        d.hit(hit(7,40,.4));assertEquals(120,d.total());assertTrue(d.visible(1.2));assertFalse(d.visible(1.4));
    }
    @Test void aDifferentTargetOrAFullSecondWithoutHitsStartsAFreshTotal() {
        var d=new LauncherDamageDisplay();d.hit(hit(1,40,0));d.hit(hit(2,12,.2));assertEquals(12,d.total());
        d.hit(hit(2,12,1.2));assertEquals(12,d.total());d.hit(hit(2,12,1.3));assertEquals(24,d.total());
    }
    @Test void healthAndAbsorptionAreAddedBeforeDisplayRounding() {
        var d=new LauncherDamageDisplay();d.hit(new LauncherDamageDisplay.Entry(1,.25f,.75f,true,false,true,1,2,3,0));
        d.hit(new LauncherDamageDisplay.Entry(1,.25f,.75f,true,false,false,1,2,3,.1));
        assertEquals(2,d.total());assertTrue(d.latest().head());assertEquals(2,d.floating(.2).size());
        d.hit(hit(1,Float.NaN,.3));assertEquals(2,d.total());
    }
    @Test void floatingHistoryIsBoundedExpiresAndClearsOnWorldChange() {
        var d=new LauncherDamageDisplay();for(int i=0;i<40;i++)d.hit(hit(1,1,i*.01));
        assertEquals(12,d.floating(.4).size());assertTrue(d.floating(2).isEmpty());
        d.reset();assertEquals(0,d.total());assertFalse(d.visible(2));assertNull(d.latest());
    }
}
