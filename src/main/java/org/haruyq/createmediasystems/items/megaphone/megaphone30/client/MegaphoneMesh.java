package org.haruyq.createmediasystems.items.megaphone.megaphone30.client;

import static org.haruyq.createmediasystems.client.mesh.Mesh.box;
import static org.haruyq.createmediasystems.client.mesh.Mesh.quad;

import org.haruyq.createmediasystems.client.mesh.Mesh;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.*;

public final class MegaphoneMesh {
    private MegaphoneMesh(){}
    public static void draw(PoseStack p,MultiBufferSource buffers,int light,boolean black,boolean on) {
        var v=buffers.getBuffer(Mesh.solid());
        int shell=black?0x161A20:0xE7E7E1,inner=black?0x30343B:0xF4F3EB,cap=black?0x222830:0xB92027;
        horn(p,v,shell,inner,light);
        frustum(p,v,-.29f,.033f,-.085f,.041f,black?0x191C21:0xD1D0C8,light,false);
        disk(p,v,-.29f,.033f,black?0x0E1013:0x3B3D3A,light);
        // Long cylindrical body and end cap, with moulded ribs and an amplifier control deck.
        frustum(p,v,-.086f,.063f,.18f,.066f,shell,light,false);
        frustum(p,v,.18f,.067f,.215f,.06f,cap,light,false);disk(p,v,.215f,.06f,cap,light);
        for(int i=0;i<9;i++) {
            double a=i*2*Math.PI/9;float x=(float)Math.cos(a)*.062f,y=(float)Math.sin(a)*.062f;
            box(p,v,x-.003f,y-.003f,.18f,x+.003f,y+.003f,.208f,black?0x41464C:0xD34746,light);
        }
        box(p,v,-.031f,.061f,.022f,.031f,.071f,.145f,0x101419,light);
        box(p,v,-.019f,.071f,.045f,.005f,.075f,.069f,0x59626C,light);
        box(p,v,.012f,.071f,.048f,.02f,.076f,.056f,on?0x66ED94:0x582B27,on?Mesh.FULL_BRIGHT:light);
        // Rear USB/microSD cover, strap eyelet and a sloped pistol handle below the housing.
        box(p,v,-.037f,-.024f,.215f,.037f,.018f,.218f,0x12171C,light);
        p.pushPose();p.translate(0,-.05,.11);p.mulPose(Axis.XP.rotationDegrees(-17));
        box(p,v,-.031f,-.185f,-.031f,.031f,.005f,.031f,shell,light);
        for(int i=0;i<5;i++)box(p,v,-.032f,-.16f+i*.024f,-.032f,.032f,-.154f+i*.024f,-.029f,black?0x323840:0xBCBEBB,light);
        box(p,v,-.013f,-.089f,-.045f,.013f,-.021f,-.031f,0x14171C,light);
        box(p,v,-.016f,-.199f,-.025f,.016f,-.182f,.025f,0x11151A,light);
        // Flat webbing loop hangs below the wrist and curves back to the rear eyelet.
        for(int i=0;i<20;i++){
            double a=i*2*Math.PI/20,b=(i+1)*2*Math.PI/20;
            float x=(float)Math.cos(a)*.048f,y=-.23f+(float)Math.sin(a)*.024f,xx=(float)Math.cos(b)*.048f,yy=-.23f+(float)Math.sin(b)*.024f;
            float nx=(float)Math.cos((a+b)/2)*.024f,ny=(float)Math.sin((a+b)/2)*.048f,n=(float)Math.sqrt(nx*nx+ny*ny);
            quad(p,v,x,y,-.005f,xx,yy,-.005f,xx,yy,.008f,x,y,.008f,nx/n,ny/n,0,0x15191F,light);
        }
        p.popPose();
        // Font is last, after all mesh vertices have been submitted.
        var font=Minecraft.getInstance().font;
        for(int side:new int[]{-1,1}) {
            p.pushPose();p.translate(side*.066,.018,.052);p.mulPose(Axis.YP.rotationDegrees(side*90));p.scale(.0016f,-.0016f,.0016f);
            font.drawInBatch("SBL  30W",-font.width("SBL  30W")/2f,0,black?0xFFDDDFDE:0xFF3B3C3C,false,p.last().pose(),buffers,Font.DisplayMode.NORMAL,0,light);p.popPose();
        }
    }
    static void horn(PoseStack p,VertexConsumer v,int shell,int inner,int light) {
        frustum(p,v,-.295f,.168f,-.085f,.063f,shell,light,false);
        frustum(p,v,-.322f,.176f,-.295f,.176f,0x101319,light,false);
        annulus(p,v,-.295f,.176f,.168f,0x15191F,light,1);
        annulus(p,v,-.322f,.176f,.163f,0x15191F,light,-1);
        frustum(p,v,-.322f,.163f,-.309f,.163f,0x15191F,light,true);
        frustum(p,v,-.309f,.163f,-.087f,.055f,inner,light,true);
        // The driver backplate closes the throat too; the body must not be visible through an open annulus.
        annulus(p,v,-.087f,.055f,0,inner,light,-1);
        annulus(p,v,-.085f,.063f,0,shell,light,1);
    }
    private static void frustum(PoseStack p,VertexConsumer v,float z0,float r0,float z1,float r1,int color,int light,boolean inside) {
        for(int i=0;i<48;i++) {
            double a=i*Math.PI/24,b=(i+1)*Math.PI/24;float c=(float)Math.cos(a),s=(float)Math.sin(a),cc=(float)Math.cos(b),ss=(float)Math.sin(b);
            float nz=(r0-r1)/(z1-z0),length=(float)Math.sqrt(1+nz*nz),normal=inside?-1:1;
            quad(p,v,r0*c,r0*s,z0,r1*c,r1*s,z1,r1*cc,r1*ss,z1,r0*cc,r0*ss,z0,normal*c/length,normal*s/length,normal*nz/length,color,light);
        }
    }
    private static void disk(PoseStack p,VertexConsumer v,float z,float r,int color,int light){annulus(p,v,z,r,0,color,light,-1);}
    private static void annulus(PoseStack p,VertexConsumer v,float z,float r,float inner,int color,int light,int facing){
        Mesh.ring(p,v,0,0,z,r,inner,48,facing,color,light);
    }
}
