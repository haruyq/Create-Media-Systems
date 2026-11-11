package org.haruyq.createmediasystems.partybox.client;

import static org.haruyq.createmediasystems.client.mesh.Mesh.box;
import static org.haruyq.createmediasystems.client.mesh.Mesh.quad;

import org.haruyq.createmediasystems.client.mesh.CellLight;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.haruyq.createmediasystems.partybox.PartyBoxSpecs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/** Original mesh, measured in metres from the manufacturer's drawing; no borrowed product-photo textures. */
public final class PartyBoxMesh {
    private static final int FULL=Mesh.FULL_BRIGHT, DARK=0x23252A, RUBBER=0x101115, METAL=0x737C85;
    private static final VertexConsumer NONE=new VertexConsumer(){
        public VertexConsumer addVertex(float x,float y,float z){return this;}public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    };
    private PartyBoxMesh() {}
    /** The light show's colour at {@code time} seconds in light mode {@code mode}: STARS (3) is nearly white. */
    public static int lightColor(double time,int mode) {
        return hue(time*.045,mode==3?.18f:.80f);
    }
    private static int hue(double cycle,float saturation) {
        return Mth.hsvToRgb((float)(cycle%1),saturation,.98f);
    }
    public static void draw(PoseStack p,MultiBufferSource buffers,int light,float handle,float wheel,double time,int mode,float beat,boolean powered) {
        drawPass(p,buffers,light,handle,wheel,time,mode,beat,powered,false);
        if(powered)drawPass(p,buffers,light,handle,wheel,time,mode,beat,true,true);
    }
    private static void drawPass(PoseStack p,MultiBufferSource buffers,int light,float handle,float wheel,double time,int mode,float beat,boolean powered,boolean emission) {
        var target=buffers.getBuffer(emission?Mesh.emissive():Mesh.solid());
        VertexConsumer raw=emission?target:NONE;
        int color=lightColor(time,mode);
        VertexConsumer v=emission?NONE:new CellLight(p.last().pose(),0,.34f,-.23f,.8f,color,
                powered&&mode!=0?.55f+beat*.6f:0).wrap(target);
        // Tyres turn independently of the leaned cabinet and remain on the ground.
        for(int side:new int[]{-1,1}) {
            p.pushPose();p.translate(side*.135,.058,.135);p.mulPose(Axis.XP.rotation(wheel/.058f));
            cylinderX(p,v,.058f,.031f,RUBBER,light);
            cylinderX(p,v,.032f,.032f,0x42464B,light);
            for(int i=0;i<12;i++) {
                p.pushPose();p.mulPose(Axis.XP.rotationDegrees(i*30));
                box(p,v,-.031f,.047f,-.007f,.031f,.058f,.007f,0x2D3035,light);p.popPose();
            }
            p.popPose();
        }
        p.pushPose();p.translate(0,.058,.135);p.mulPose(Axis.XP.rotationDegrees(handle*19));p.translate(0,-.058,-.135);
        rounded(p,v,PartyBoxSpecs.WIDTH,.624f,.32f,.035f,.029f,DARK,light);
        p.pushPose();p.translate(0,0,.0325f);
        rounded(p,v,.313f,.585f,.009f,.067f,.027f,0x121318,light,-.197f);
        // Two 165 mm drivers with rubber surrounds and actual recessed cones.
        for(float y:new float[]{.218f,.458f}) {
            ring(p,v,0,y,-.205f,.117f,.090f,RUBBER,light);
            ring(p,v,0,y,-.208f,.0825f,.058f,0x303239,light);
            disk(p,v,0,y,-.210f,.058f,0x16191E,light);
            if(powered&&mode!=0) {
                float brightness=mode==2? .32f+beat*.68f:.65f+beat*.35f;
                int ringColor=y>.3f?color:hue(time*.045+.15,.80f);
                ring(p,raw,0,y,-.217f,.109f,.090f,ringColor,FULL,(float)time+(y>.3f?0:1.8f),brightness);
            }
        }
        // Dome tweeters, star points and a perforated front grille over the light rings.
        for(int side:new int[]{-1,1}) {
            disk(p,v,side*.106f,.344f,-.218f,.013f,0x090A0D,light);
            if(powered&&mode!=0) for(int i=0;i<3;i++) {
                float y=.16f+i*.18f;
                box(p,raw,side*.117f-.0015f,y-.0025f,-.224f,side*.117f+.0015f,y+.0025f,-.222f,
                        mode==3?0xFFFFFF:color,FULL);
            }
        }
        for(int i=-12;i<=12;i++) {
            float x=i*.011f;
            box(p,v,x-.0006f,.085f,-.224f,x+.0006f,.622f,-.223f,0x0B0C10,light);
        }
        for(int i=0;i<49;i++) {
            float y=.085f+i*.011f;
            box(p,v,-.137f,y-.00055f,-.225f,.137f,y+.00055f,-.224f,0x0B0C10,light);
        }
        // Top/bottom light trails follow the chamfered front contour.
        for(float y:new float[]{.073f,.625f}) {
            box(p,powered&&mode!=0?raw:v,-.12f,y,-.211f,.12f,y+.003f,-.207f,powered&&mode!=0?color:RUBBER,powered&&mode!=0?FULL:light);
        }
        p.popPose();
        // Recessed top deck: two knobs, media keys, Bluetooth and Bass Boost.
        box(p,v,-.122f,.653f,-.13f,.122f,.661f,.12f,0x454950,light);
        for(int side:new int[]{-1,1}) {
            box(p,v,side*.077f-.023f,.661f,-.087f,side*.077f+.023f,.669f,-.041f,RUBBER,light);
            box(p,v,side*.077f-.003f,.669f,-.083f,side*.077f+.003f,.6691f,-.067f,0xC8CED2,light);
        }
        for(int i=0;i<5;i++)box(p,v,-.084f+i*.037f,.663f,.007f,-.066f+i*.037f,.667f,.023f,RUBBER,light);
        box(p,powered?raw:v,-.011f,.667f,.013f,.011f,.669f,.017f,powered?0x9DE6D2:0x4E565D,powered?FULL:light);
        // Rear carry recess, input cover, bass reflex port and AC hatch.
        p.pushPose();p.translate(0,0,-.0135f);
        box(p,v,-.104f,.565f,.193f,.104f,.603f,.199f,RUBBER,light);
        box(p,v,-.083f,.567f,.199f,.083f,.572f,.201f,0xF54D26,light);
        box(p,v,-.062f,.377f,.193f,.062f,.475f,.200f,RUBBER,light);
        box(p,v,-.055f,.294f,.195f,.055f,.349f,.201f,0xE84B26,light);
        box(p,v,-.052f,.298f,.201f,.052f,.345f,.202f,RUBBER,light);
        box(p,v,-.090f,.154f,.193f,.090f,.262f,.205f,RUBBER,light);
        box(p,v,-.063f,.171f,.205f,.063f,.245f,.206f,0x050507,light);
        box(p,v,-.045f,.071f,.193f,.045f,.116f,.201f,RUBBER,light);
        p.popPose();
        // Two telescoping stages and a real open handle loop.
        for(int side:new int[]{-1,1}) {
            float x=side*.099f;
            box(p,v,x-.010f,.575f,.143f,x+.010f,.641f+handle*.17f,.164f,0x515960,light);
            box(p,v,x-.0065f,.630f,.147f,x+.0065f,.647f+handle*.36f,.160f,METAL,light);
        }
        float grip=.641f+handle*.36f;
        box(p,v,-.112f,grip,.136f,.112f,grip+.025f,.169f,RUBBER,light);
        box(p,v,-.078f,grip+.001f,.135f,.078f,grip+.007f,.137f,0xF54D26,light);
        for(int side:new int[]{-1,1})box(p,v,side*.107f-.012f,.028f,-.147f,side*.107f+.012f,.047f,-.106f,RUBBER,light);
        // Font draw comes last: acquiring a different unfixed RenderType may close the mesh builder.
        if(!emission){
        p.pushPose();p.translate(0,.345,-.1955);p.mulPose(Axis.YP.rotationDegrees(180));p.scale(.004f,-.004f,.004f);
        var font=Minecraft.getInstance().font;
        font.drawInBatch("SBL",-font.width("SBL")/2f,0,0xFFFF5C2B,false,p.last().pose(),buffers,Font.DisplayMode.NORMAL,0,FULL);
        p.popPose();}
        p.popPose();
    }
    private static void cylinderX(PoseStack p,VertexConsumer v,float r,float half,int color,int light) {
        for(int i=0;i<24;i++) {
            double a=i*Math.PI/12,b=(i+1)*Math.PI/12;
            float y=(float)Math.cos(a)*r,z=(float)Math.sin(a)*r,yy=(float)Math.cos(b)*r,zz=(float)Math.sin(b)*r;
            quad(p,v,-half,y,z,half,y,z,half,yy,zz,-half,yy,zz,0,y/r,z/r,color,light);
            quad(p,v,half,0,0,half,yy,zz,half,y,z,half,0,0,1,0,0,color,light);
            quad(p,v,-half,0,0,-half,y,z,-half,yy,zz,-half,0,0,-1,0,0,color,light);
        }
    }
    private static void disk(PoseStack p,VertexConsumer v,float x,float y,float z,float r,int color,int light) { ring(p,v,x,y,z,r,0,color,light); }
    private static void ring(PoseStack p,VertexConsumer v,float x,float y,float z,float outer,float inner,int color,int light) {
        Mesh.ring(p,v,x,y,z,outer,inner,64,-1,color,light);
    }
    /** A light ring whose brightness chases round it with {@code time}. */
    private static void ring(PoseStack p,VertexConsumer v,float x,float y,float z,float outer,float inner,int color,int light,float time,float strength) {
        Mesh.ring(p,v,x,y,z,outer,inner,64,-1,a->{
            float k=strength*(.45f+.55f*(float)Math.pow(.5+.5*Math.sin(a*2-time*2),2));
            return ((int)((color>>16&255)*k)<<16)|((int)((color>>8&255)*k)<<8)|(int)((color&255)*k);
        },light);
    }
    private static void rounded(PoseStack p,VertexConsumer v,float w,float h,float d,float y,float radius,int color,int light) {rounded(p,v,w,h,d,y,radius,color,light,0);}
    private static void rounded(PoseStack p,VertexConsumer v,float w,float h,float d,float y,float radius,int color,int light,float z) {
        float[] xs=new float[20],ys=new float[20];
        for(int corner=0;corner<4;corner++)for(int i=0;i<5;i++) {
            double angle=(corner*90+i*22.5)*Math.PI/180;
            float cx=corner==0||corner==3?w/2-radius:-w/2+radius;
            float cy=corner<2?y+h-radius:y+radius;
            int at=corner*5+i;xs[at]=cx+(float)Math.cos(angle)*radius;ys[at]=cy+(float)Math.sin(angle)*radius;
        }
        for(int i=0;i<20;i++) {
            int j=(i+1)%20;float x=xs[i],yy=ys[i],xx=xs[j],yyy=ys[j];
            quad(p,v,0,y+h/2,z-d/2,x,yy,z-d/2,xx,yyy,z-d/2,0,y+h/2,z-d/2,0,0,-1,color,light);
            quad(p,v,0,y+h/2,z+d/2,xx,yyy,z+d/2,x,yy,z+d/2,0,y+h/2,z+d/2,0,0,1,color,light);
            float nx=yyy-yy,ny=x-xx,len=(float)Math.hypot(nx,ny);
            if(len>0)quad(p,v,x,yy,z-d/2,x,yy,z+d/2,xx,yyy,z+d/2,xx,yyy,z-d/2,nx/len,ny/len,0,color,light);
        }
    }
}
