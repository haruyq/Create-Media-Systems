package org.haruyq.createmediasystems.items.launcher;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;

/** A target-local attachment frame. Normalized offsets follow crouching and changes in body size. */
public record DiscAttachmentFrame(Vec3 origin,float yaw,float pitch,double width,double height) {
    public static DiscAttachmentFrame at(LivingEntity target,boolean head,float partial) {
        Vec3 origin=new Vec3(Mth.lerp(partial,target.xOld,target.getX()),
                Mth.lerp(partial,target.yOld,target.getY())+(head?target.getEyeHeight():0),
                Mth.lerp(partial,target.zOld,target.getZ()));
        float yaw=Mth.rotLerp(partial,head?target.yHeadRotO:target.yBodyRotO,head?target.yHeadRot:target.yBodyRot);
        float pitch=head?Mth.lerp(partial,target.xRotO,target.getXRot()):0;
        return new DiscAttachmentFrame(origin,yaw,pitch,Math.max(.1,target.getBbWidth()),
                Math.max(.1,head?Math.min(.68,target.getBbHeight()*.3):target.getBbHeight()));
    }
    /** Dragon hitboxes have their own animated positions and cannot use the parent's torso frame. */
    public static DiscAttachmentFrame atDragonPart(EnderDragon dragon,int index,float partial) {
        var parts=dragon.getSubEntities();index=Math.clamp(index,0,parts.length-1);var part=parts[index];
        Vec3 origin=interpolated(part,partial);
        float yaw=Mth.rotLerp(partial,dragon.yRotO,dragon.getYRot()),pitch=0;
        if(index==0 || index==1 || index>=3 && index<=5) {
            int previous=index==0?1:index==1?2:index==3?2:index-1;
            var axis=origin.subtract(interpolated(parts[previous],partial));
            if(axis.lengthSqr()>1e-6) {
                yaw=(float)Math.toDegrees(Math.atan2(-axis.x,axis.z));
                pitch=(float)-Math.toDegrees(Math.atan2(axis.y,axis.horizontalDistance()));
            }
        }
        return new DiscAttachmentFrame(origin,yaw,pitch,Math.max(.1,part.getBbWidth()),Math.max(.1,part.getBbHeight()));
    }
    private static Vec3 interpolated(net.minecraft.world.entity.Entity e,float t) {
        return new Vec3(Mth.lerp(t,e.xOld,e.getX()),Mth.lerp(t,e.yOld,e.getY()),Mth.lerp(t,e.zOld,e.getZ()));
    }
    public Vec3 local(Vec3 point) {
        Vec3 v=localDirection(point.subtract(origin));return new Vec3(v.x/width,v.y/height,v.z/width);
    }
    public Vec3 world(Vec3 point) {return origin.add(worldDirection(new Vec3(point.x*width,point.y*height,point.z*width)));}
    public Vec3 localDirection(Vec3 v) {return rotateX(rotateY(v,-yaw),-pitch);}
    public Vec3 worldDirection(Vec3 v) {return rotateY(rotateX(v,pitch),yaw);}
    private static Vec3 rotateY(Vec3 v,float degrees) {
        double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);return new Vec3(v.x*c-v.z*s,v.y,v.x*s+v.z*c);
    }
    private static Vec3 rotateX(Vec3 v,float degrees) {
        double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);return new Vec3(v.x,v.y*c-v.z*s,v.y*s+v.z*c);
    }
}
