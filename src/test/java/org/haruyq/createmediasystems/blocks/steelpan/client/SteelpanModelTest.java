package org.haruyq.createmediasystems.blocks.steelpan.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanLayout;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class SteelpanModelTest {
    private static final long IMPACT=1_000_000_000L;

    private static SteelpanModel.Geometry geometry() throws Exception {
        try (var input=SteelpanModelTest.class.getResourceAsStream("/assets/create_media_systems/models/block/steelpan/steelpan.mesh.json")) {
            assertNotNull(input);
            return new Gson().fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),SteelpanModel.Geometry.class);
        }
    }

    @Test void finiteMeshHas29SeparateFieldsTwoMalletsAndAnOpenConcaveBowl() throws Exception {
        var mesh=geometry();var names=new HashSet<String>();var pitches=new HashSet<Integer>();
        int mallets=0;float radius=0;
        for (var p:mesh.parts()) {
            assertTrue(names.add(p.name()));
            if ("note".equals(p.motion())) assertTrue(pitches.add(p.midi()));
            if ("mallet".equals(p.motion())) mallets++;
            for (var q:p.quads()) {
                assertEquals(1,new Vector3f(q.normal()).length(),1e-5);
                assertEquals(4,q.vertices().length);
                for (var v:q.vertices()) {
                    assertEquals(6,v.length);
                    for (float f:v) assertTrue(Float.isFinite(f));
                    assertTrue(v[3]>=0&&v[3]<=1&&v[4]>=0&&v[4]<=1);
                    assertTrue(v[5]>=0&&v[5]<=1);
                    if (p.name().equals("hammered_bowl") || p.name().equals("skirt_and_rolled_rims")) {
                        radius=Math.max(radius,(float)Math.hypot(v[0],v[2]));
                    }
                }
            }
        }
        assertEquals(29,pitches.size());assertEquals(2,mallets);
        assertEquals(.5842,2*radius,1e-5);
        // The shell dimensions exclude the suspension fittings.
        var shell=mesh.parts().stream().filter(p->p.name().equals("hammered_bowl")).findFirst().orElseThrow();
        float shellBottom=Float.POSITIVE_INFINITY;
        for (var q:shell.quads()) for (var v:q.vertices()) shellBottom=Math.min(shellBottom,v[1]);
        assertEquals(.2159,.004-shellBottom,1e-5);
        assertTrue(SteelpanLayout.bowl(0,0)<-.2);
        assertTrue(SteelpanLayout.bowl(.27f,0)>-.04);
    }

    @Test void strikesFlexOnlyTheSelectedFieldAndNeverItsFixedGroove() throws Exception {
        var mesh=geometry();
        for (var p:mesh.parts()) if ("note".equals(p.motion())) {
            var state=new SteelpanState();
            assertTrue(state.strikeAt(p.midi(),1,0,IMPACT));
            assertEquals(0,state.vibration(p.midi(),IMPACT-1));
            float movement=state.vibration(p.midi(),IMPACT+10_000_000L);
            assertTrue(movement>.0003,p.name());
            boolean anchored=false,moving=false;
            for (var q:p.quads()) for (var v:q.vertices()) {
                if (v[5]==0) { anchored=true;assertEquals(v[1],v[1]+movement*v[5]); }
                if (v[5]>.99) moving=true;
                float y=v[1]+movement*v[5];
                assertTrue(y>SteelpanLayout.bowl(v[0],v[2])+.0002f,"Never clip through the bowl");
            }
            assertTrue(anchored&&moving);
            for (int other=60;other<=88;other++) if(other!=p.midi()) assertEquals(0,state.vibration(other,IMPACT+10_000_000L));
            assertEquals(0,state.vibration(p.midi(),IMPACT+1_000_000_000L));
        }
    }

    @Test void rubberTipReachesEveryFieldThenReboundsAndBothHandsReturnToRest() {
        for (int hand=0;hand<2;hand++) for (int midi=60;midi<=88;midi++) {
            var state=new SteelpanState();state.strikeAt(midi,.8f,hand,IMPACT);
            var tip=SteelpanModel.malletTip(state,hand,IMPACT);
            var target=SteelpanLayout.note(midi).center();
            assertEquals(target[0],tip.x,1e-6);assertEquals(target[2],tip.z,1e-6);
            assertEquals(SteelpanLayout.bowl(tip.x,tip.z)+.0028,tip.y-.008,1e-6);
            assertTrue(SteelpanModel.malletTip(state,hand,IMPACT+40_000_000L).y>tip.y+.07);
            var rest=SteelpanModel.malletTip(new SteelpanState(),hand,IMPACT);
            assertEquals(rest,SteelpanModel.malletTip(state,hand,IMPACT+1_100_000_000L));
            // The shaft has a constant length and clears the concave surface, including the rim crossing.
            var rotation=SteelpanModel.malletRotation(state,hand,IMPACT);
            for (int i=0;i<=20;i++) {
                var shaft=new Vector3f(0,.014f+i*.0097f,0).rotate(rotation).add(tip);
                if (Math.hypot(shaft.x,shaft.z)<.288) {
                    assertTrue(shaft.y-.004>SteelpanLayout.bowl(shaft.x,shaft.z),"Shaft penetrates "+midi+" hand "+hand);
                }
            }
        }
    }

    @Test void preparationAndFastRollTransitionsAreContinuous() {
        var state=new SteelpanState();state.strikeAt(60,1,0,IMPACT);
        state.strikeAt(79,.8f,0,IMPACT+120_000_000L);
        state.strikeAt(84,.8f,0,IMPACT+1_500_000_000L);
        for (long boundary:new long[]{IMPACT-85_000_000L,IMPACT,IMPACT+35_000_000L,IMPACT+120_000_000L,
                IMPACT+550_000_000L,IMPACT+1_000_000_000L,IMPACT+1_415_000_000L}) {
            var before=SteelpanModel.malletTip(state,0,boundary-1_000);
            var after=SteelpanModel.malletTip(state,0,boundary+1_000);
            assertTrue(before.distance(after)<.0001,"Timeline discontinuity at "+boundary);
        }
        assertEquals(0,state.vibration(60,IMPACT-1));
        assertTrue(state.mallet(1,IMPACT).activity()==0,"Other hand does not move");
    }

    @Test void longPerformancesStayBoundedAndInvalidInputsCannotPoisonThePose() {
        var state=new SteelpanState();
        assertFalse(state.strikeAt(59,1,0,1));assertFalse(state.strikeAt(89,1,0,1));
        assertFalse(state.strikeAt(60,Float.NaN,0,1));assertFalse(state.strikeAt(60,1,2,1));
        assertFalse(state.strikeAt(60,0,0,1));
        for(int i=0;i<10000;i++) assertTrue(state.strikeAt(60+i%29,2,i%2,IMPACT+i*100_000_000L));
        assertEquals(64,state.queuedStrokes());
        assertFalse(state.strikeAt(60,1,1,IMPACT));
        state.reset();assertEquals(0,state.queuedStrokes());
        assertEquals(0,state.vibration(60,Long.MAX_VALUE));assertEquals(0,state.sway(IMPACT));
    }
}
