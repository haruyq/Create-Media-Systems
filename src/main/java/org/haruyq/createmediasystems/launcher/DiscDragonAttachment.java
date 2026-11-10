package org.haruyq.createmediasystems.launcher;

import java.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.*;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Common-side equivalent of the vanilla dragon's animated bone transforms.
 * Collider boxes are deliberately much larger than the visible mesh. An attachment must use the mesh.
 * No client/render classes: the server persists a bone-local point and every listener animates it identically.
 */
public final class DiscDragonAttachment {
    public record Bone(int id,int part,Matrix4d world,Matrix4d inverse,List<AABB> boxes) {
        public Vec3 position(Vec3 local){var p=world.transformPosition(new Vector3d(local.x,local.y,local.z));return new Vec3(p.x,p.y,p.z);}
        public Vec3 local(Vec3 point){var p=inverse.transformPosition(new Vector3d(point.x,point.y,point.z));return new Vec3(p.x,p.y,p.z);}
        public Vec3 direction(Vec3 local){var v=world.transformDirection(new Vector3d(local.x,local.y,local.z));return new Vec3(v.x,v.y,v.z).normalize();}
        public Vec3 localDirection(Vec3 vector){var v=inverse.transformDirection(new Vector3d(vector.x,vector.y,vector.z));return new Vec3(v.x,v.y,v.z).normalize();}
    }
    public record Contact(int bone,Vec3 point,Vec3 direction){}
    private record Cached(int tick,int partial,long x,long y,long z,int yaw,int flap,List<Bone> bones){}
    private static final Map<EnderDragon,Cached> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    private static final float DEG=(float)(Math.PI/180);
    private DiscDragonAttachment(){}
    public static Bone bone(EnderDragon dragon,int id,float partial){var bones=bones(dragon,partial);return bones.get(Math.clamp(id,0,bones.size()-1));}
    public static Contact contact(EnderDragon dragon,int part,Vec3 point,Vec3 incoming) {
        Bone selected=null;Vec3 chosen=null;double nearest=Double.POSITIVE_INFINITY;
        for(var bone:bones(dragon,1))if(bone.part==part) {
            var at=bone.local(point);var direction=bone.localDirection(incoming);
            for(var box:bone.boxes) {
                var entry=box.clip(at.subtract(direction.scale(3)),at.add(direction.scale(12)));
                Vec3 on=entry.orElseGet(()->surface(box,at));double distance=bone.position(on).distanceToSqr(point);
                if(distance<nearest){nearest=distance;selected=bone;chosen=on;}
            }
        }
        if(selected==null)selected=bone(dragon,6,1);
        var direction=selected.localDirection(incoming);if(chosen==null)chosen=surface(selected.boxes.getFirst(),selected.local(point));
        return new Contact(selected.id,chosen.add(direction.scale(.08)),direction);
    }
    private static Vec3 surface(AABB box,Vec3 p) {
        double x=Math.clamp(p.x,box.minX,box.maxX),y=Math.clamp(p.y,box.minY,box.maxY),z=Math.clamp(p.z,box.minZ,box.maxZ);
        if(box.contains(p)) {
            double[] d={p.x-box.minX,box.maxX-p.x,p.y-box.minY,box.maxY-p.y,p.z-box.minZ,box.maxZ-p.z};int face=0;
            for(int i=1;i<6;i++)if(d[i]<d[face])face=i;
            switch(face){case 0->x=box.minX;case 1->x=box.maxX;case 2->y=box.minY;case 3->y=box.maxY;case 4->z=box.minZ;default->z=box.maxZ;}
        }
        return new Vec3(x,y,z);
    }
    public static List<Bone> bones(EnderDragon dragon,float partial) {
        var cached=CACHE.get(dragon);int bits=Float.floatToIntBits(partial),yaw=Float.floatToIntBits(dragon.getYRot()),flap=Float.floatToIntBits(dragon.flapTime);
        long x=Double.doubleToLongBits(dragon.getX()),y=Double.doubleToLongBits(dragon.getY()),z=Double.doubleToLongBits(dragon.getZ());
        if(cached!=null && cached.tick==dragon.tickCount && cached.partial==bits && cached.x==x && cached.y==y && cached.z==z && cached.yaw==yaw && cached.flap==flap)return cached.bones;
        var result=build(dragon,partial);CACHE.put(dragon,new Cached(dragon.tickCount,bits,x,y,z,yaw,flap,result));return result;
    }
    private static List<Bone> build(EnderDragon d,float t) {
        Bone[] result=new Bone[23];float flap=Mth.lerp(t,d.oFlapTime,d.flapTime),phase=flap*(float)(Math.PI*2);
        float bob=(float)(Math.sin(phase-1)+1);bob=(bob*bob+bob*2)*.05f;
        float bank=Mth.wrapDegrees((float)(d.getLatencyPos(5,t)[0]-d.getLatencyPos(10,t)[0]));
        float mean=Mth.wrapDegrees((float)(d.getLatencyPos(5,t)[0]+bank/2));
        var base=new Matrix4d().translate(Mth.lerp(t,d.xOld,d.getX()),Mth.lerp(t,d.yOld,d.getY()),Mth.lerp(t,d.zOld,d.getZ()))
                .rotateY((float)-d.getLatencyPos(7,t)[0]*DEG).rotateX((float)(d.getLatencyPos(5,t)[1]-d.getLatencyPos(10,t)[1])*10*DEG)
                .translate(0,0,1).scale(-1,-1,1).translate(0,-1.501f,0).translate(0,bob-2,-3).rotateX(bob*2*DEG);
        float px=0,py=20,pz=-12;var history=d.getLatencyPos(6,t);
        for(int i=0;i<5;i++) {
            var current=d.getLatencyPos(5-i,t);
            float rx=(float)Math.cos(i*.45f+phase)*.15f+d.getHeadPartYOffset(i,history,current)*DEG*1.5f*5;
            float ry=Mth.wrapDegrees((float)(current[0]-history[0]))*DEG*1.5f,rz=-Mth.wrapDegrees((float)(current[0]-mean))*DEG*1.5f;
            put(result,1+i,1,pose(base,px,py,pz,rx,ry,rz),box(-5,-5,-5,5,5,5));
            py+=Mth.sin(rx)*10;pz-=Mth.cos(ry)*Mth.cos(rx)*10;px-=Mth.sin(ry)*Mth.cos(rx)*10;
        }
        var head=d.getLatencyPos(0,t);
        put(result,0,0,pose(base,px,py,pz,Mth.wrapDegrees(d.getHeadPartYOffset(6,history,head))*DEG*1.5f*5,
                Mth.wrapDegrees((float)(head[0]-history[0]))*DEG,-Mth.wrapDegrees((float)(head[0]-mean))*DEG),
                box(-8,-8,-10,8,8,6),box(-6,-1,-24,6,4,-8));
        var body=new Matrix4d(base).translate(0,1,0).rotateZ(-bank*1.5f*DEG).translate(0,-1,0);
        put(result,6,2,pose(body,0,4,8,0,0,0),box(-12,0,-16,12,24,48));
        float wingX=.125f-(float)Math.cos(phase)*.2f,wingZ=-((float)Math.sin(phase)+.125f)*.8f,tipZ=((float)Math.sin(phase+2)+.5f)*.75f;
        var left=pose(body,12,5,2,wingX,-.25f,wingZ);
        put(result,7,7,left,box(0,-4,-4,56,4,4),box(0,-.35,2,56,.35,58));
        put(result,8,7,pose(left,56,0,0,0,0,tipZ),box(0,-2,-2,56,2,2),box(0,-.35,2,56,.35,58));
        var right=pose(body,-12,5,2,wingX,.25f,-wingZ);
        put(result,9,6,right,box(-56,-4,-4,0,4,4),box(-56,-.35,2,0,.35,58));
        put(result,10,6,pose(right,-56,0,0,0,0,-tipZ),box(-56,-2,-2,0,2,2),box(-56,-.35,2,0,.35,58));
        px=0;py=10;pz=60;float bend=0;history=d.getLatencyPos(11,t);
        for(int i=0;i<12;i++) {
            var current=d.getLatencyPos(12+i,t);bend+=Mth.sin(i*.45f+phase)*.05f;
            float rx=bend+(float)(current[1]-history[1])*DEG*1.5f*5,ry=(Mth.wrapDegrees((float)(current[0]-history[0]))*1.5f+180)*DEG;
            float rz=Mth.wrapDegrees((float)(current[0]-mean))*DEG*1.5f;
            put(result,11+i,3+Math.min(2,i/4),pose(base,px,py,pz,rx,ry,rz),box(-5,-5,-5,5,5,5));
            py+=Mth.sin(rx)*10;pz-=Mth.cos(ry)*Mth.cos(rx)*10;px-=Mth.sin(ry)*Mth.cos(rx)*10;
        }
        return List.of(result);
    }
    private static Matrix4d pose(Matrix4d parent,float x,float y,float z,float rx,float ry,float rz){return new Matrix4d(parent).translate(x/16,y/16,z/16).rotateZYX(rz,ry,rx);}
    private static AABB box(double x,double y,double z,double xx,double yy,double zz){return new AABB(x/16,y/16,z/16,xx/16,yy/16,zz/16);}
    private static void put(Bone[] bones,int id,int part,Matrix4d matrix,AABB... boxes){bones[id]=new Bone(id,part,matrix,new Matrix4d(matrix).invert(),List.of(boxes));}
}
