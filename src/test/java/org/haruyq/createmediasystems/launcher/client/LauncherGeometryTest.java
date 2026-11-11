package org.haruyq.createmediasystems.launcher.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

class LauncherGeometryTest {
    static final class Mesh implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();
        public VertexConsumer addVertex(float x,float y,float z) { vertices.add(new float[]{x,y,z,0,0,0});return this; }
        public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        public VertexConsumer setUv(float u,float v) { return this; }
        public VertexConsumer setUv1(int u,int v) { return this; }
        public VertexConsumer setUv2(int u,int v) { return this; }
        public VertexConsumer setNormal(float x,float y,float z) {
            float[] p=vertices.getLast();p[3]=x;p[4]=y;p[5]=z;return this;
        }
        void assertOutward() {
            assertTrue(!vertices.isEmpty() && vertices.size()%4==0);
            for(int i=0;i<vertices.size();i+=4) {
                double area=0;
                for(int[] triangle:new int[][]{{0,1,2},{2,3,0}}) {
                    float[] a=vertices.get(i+triangle[0]),b=vertices.get(i+triangle[1]),c=vertices.get(i+triangle[2]);
                    float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2];
                    float vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
                    double dot=(uy*vz-uz*vy)*a[3]+(uz*vx-ux*vz)*a[4]+(ux*vy-uy*vx)*a[5];
                    assertTrue(dot>=-1e-6,"Inward triangle at quad "+i/4);
                    area+=dot;
                }
                assertTrue(area>1e-6,"Entire face is degenerate at quad "+i/4);
            }
        }
    }

    @Test void allSixCuboidFacesSurviveBackfaceCulling() {
        Mesh tiled=new Mesh(),flat=new Mesh();
        org.haruyq.createmediasystems.client.mesh.Mesh.box(new PoseStack(),tiled,0,0,0,2,3,4,
                org.haruyq.createmediasystems.client.mesh.Mesh.Tile.METAL,0,0);
        org.haruyq.createmediasystems.client.mesh.Mesh.box(new PoseStack(),flat,0,0,0,2,3,4,0xFFFFFF,0);
        tiled.assertOutward();flat.assertOutward();
    }

    @Test void everyDiscSectorHasTheCorrectFrontFace() {
        Mesh mesh=new Mesh();
        LauncherRenderer.disc(new PoseStack(),mesh,0,0,0,3.6f,0,0);
        mesh.assertOutward();
    }
    @Test void theDiscGlowIsAnOutwardFacingRimSeparatedFromTheOpaqueBlade(){
        Mesh mesh=new Mesh();LauncherRenderer.discGlowRing(new PoseStack(),mesh,0,0);mesh.assertOutward();
        for(var v:mesh.vertices)assertTrue(Math.hypot(v[0],v[2])>3.6,"The glow must not duplicate the coplanar disc surface");
    }
    @Test void bothSidesOfTheMegaphoneHornUseWindingThatMatchesTheirActualNormals()throws Exception{
        var method=org.haruyq.createmediasystems.megaphone.client.MegaphoneMesh.class.getDeclaredMethod("frustum",PoseStack.class,VertexConsumer.class,float.class,float.class,float.class,float.class,int.class,int.class,boolean.class);
        method.setAccessible(true);
        for(boolean inside:new boolean[]{false,true}){Mesh mesh=new Mesh();method.invoke(null,new PoseStack(),mesh,-.31f,.175f,-.085f,.063f,0xEEEEEE,0,inside);mesh.assertOutward();}
    }
    @Test void theMegaphoneLipAndLiningFormAClosedShellWithoutCracks() throws Exception {
        var horn=org.haruyq.createmediasystems.megaphone.client.MegaphoneMesh.class.getDeclaredMethod("horn",PoseStack.class,VertexConsumer.class,int.class,int.class,int.class);
        horn.setAccessible(true);Mesh mesh=new Mesh();horn.invoke(null,new PoseStack(),mesh,0xEEEEEE,0xFFFFFF,0);mesh.assertOutward();
        var edges=new java.util.HashMap<String,Integer>();
        for(int i=0;i<mesh.vertices.size();i+=4)for(int j=0;j<4;j++) {
            String a=point(mesh.vertices.get(i+j)),b=point(mesh.vertices.get(i+(j+1)%4));
            if(a.equals(b))continue; // A disk's centre is repeated to represent a triangle as a quad.
            String edge=a.compareTo(b)<0?a+"/"+b:b+"/"+a;edges.merge(edge,1,Integer::sum);
        }
        for(var edge:edges.entrySet())assertTrue(edge.getValue()==2,"Exposed edge between rubber rim and horn lining: "+edge);
    }
    private static String point(float[] p){return Math.round(p[0]*1e6)+","+Math.round(p[1]*1e6)+","+Math.round(p[2]*1e6);}

    @Test void everyOpticAndReceiverVertexFollowsTheSameReloadHinge() {
        Mesh closed=new Mesh(),open=new Mesh();
        LauncherRenderer.frontAssembly(new PoseStack(),closed,closed,0,0,0,0,0);
        LauncherRenderer.frontAssembly(new PoseStack(),open,open,1,0,0,0,0);
        assertTrue(closed.vertices.size()==open.vertices.size());closed.assertOutward();open.assertOutward();
        int optic=0;
        for(int i=0;i<closed.vertices.size();i++) {
            var a=closed.vertices.get(i);var b=open.vertices.get(i);
            double ay=a[1]-9,az=a[2]-8.2,by=b[1]-9,bz=b[2]-8.2;
            org.junit.jupiter.api.Assertions.assertEquals(ay*ay+az*az,by*by+bz*bz,2e-4,"No front part may use a different origin or animation");
            if(a[1]>=16.1){optic++;assertTrue(Math.hypot(a[1]-b[1],a[2]-b[2])>1,"No scope vertex may remain at its idle position during reload");}
        }
        assertTrue(optic>50,"The regression must cover the actual complete optic mesh");
    }
}
