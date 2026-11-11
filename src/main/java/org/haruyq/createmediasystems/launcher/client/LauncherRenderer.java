package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.power.ChargeableItem;
import static org.haruyq.createmediasystems.client.mesh.Mesh.FULL_BRIGHT;
import static org.haruyq.createmediasystems.client.mesh.Mesh.box;
import static org.haruyq.createmediasystems.client.mesh.Mesh.quad;
import static org.haruyq.createmediasystems.client.mesh.Mesh.vertex;
import static org.haruyq.createmediasystems.client.mesh.Mesh.Tile.*;

import org.haruyq.createmediasystems.client.mesh.CellLight;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.mesh.Tint;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.launcher.LauncherTimeline;
import org.haruyq.createmediasystems.launcher.DiscCellItem;
import org.haruyq.createmediasystems.launcher.LauncherMechanics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * A procedural, multi-part Create-style weapon mesh. Unlike one-piece JSON item
 * animation, its flywheels, latch, magazine tray, inserted disc and charging bolt
 * move independently, with no GeckoLib dependency or per-frame model baking.
 */
public final class LauncherRenderer extends BlockEntityWithoutLevelRenderer {

    public LauncherRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext display, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        var visual = LauncherClient.cellVisual(stack, level, partial);
        LauncherTimeline.Frame motion = visual.motion();
        int color = visual.color();
        // One pass: acquiring a second unfixed render type would invalidate the first BufferBuilder.
        // Core vertices supply full-bright light; receiver vertices receive a tier-colored point light.
        VertexConsumer luminous = buffers.getBuffer(Mesh.solid());
        pose.pushPose();
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        VertexConsumer mesh = new CellLight(pose.last().pose(), visual.pose().x(), visual.pose().y(), visual.pose().z(),
                18, color, visual.strength() * 1.8f).wrap(luminous);
        VertexConsumer glow = new Tint(luminous, visual.strength() > 0 ? color : 0x344148, 255);

        // Hollow magazine well: every fixed part stays OUTSIDE the cartridge's 5.2 x 5.2 feed shaft.
        box(pose, mesh, 4.9f, 7.1f, 14.9f, 11.1f, 15.6f, 16.1f, DARK, light, overlay);
        box(pose, mesh, 4.2f, 8.1f, 14.9f, 4.9f, 14.8f, 16.1f, METAL, light, overlay);
        box(pose, mesh, 11.1f, 8.1f, 14.9f, 11.8f, 14.8f, 16.1f, METAL, light, overlay);
        for (int side : new int[] {-1, 1}) {
            float x0 = side < 0 ? 3.9f : 11.1f, x1 = side < 0 ? 4.9f : 12.1f;
            box(pose, mesh, x0, 7.1f, 8.8f, x1, 9.2f, 14.9f, DARK, light, overlay);
            box(pose, mesh, x0, 14.8f, 8.8f, x1, 15.75f, 14.9f, METAL, light, overlay);
            box(pose, mesh, x0, 9.2f, 8.3f, x1, 14.8f, 8.8f, METAL, light, overlay);
            box(pose, mesh, x0, 9.2f, 14.9f, x1, 14.8f, 15.4f, METAL, light, overlay);
        }
        // The grip is behind the well; it no longer occupies the cell's insertion path.
        box(pose, mesh, 6, .4f, LauncherMechanics.GRIP_FRONT, 10, 8.4f, 18.5f, WOOD, light, overlay);
        box(pose, mesh, 5.5f, .2f, 16, 10.5f, 1.1f, 18.7f, BRASS, light, overlay);
        box(pose, mesh, 5.3f, 3, 14.9f, 10.7f, 3.7f, 16.1f, DARK, light, overlay);
        box(pose, mesh, 6.3f, 5.4f, 15, 7, 7.2f, 15.7f, BRASS, light, overlay);

        float spin = level == null ? 0 : (level.getGameTime()%360+partial)*16;
        frontAssembly(pose,mesh,glow,motion.latch(),spin,-motion.bolt()*2.4f-motion.recoil()*1.2f,light,overlay);

        // The taller lid clears the cell's top button with .45 model pixels to spare.
        pose.pushPose();
        pose.translate(8, 16.15f, 15.6f);
        pose.mulPose(Axis.XP.rotationDegrees(motion.latch() * 105));
        pose.translate(-8, -16.15f, -15.6f);
        box(pose, mesh, 4.2f, LauncherMechanics.LID_BOTTOM, 8.4f, 11.8f, 16.55f, 15.6f, METAL, light, overlay);
        box(pose, mesh, 5.32f, 16.55f, 9.1f, 10.68f, 17.1f, 14.6f, DARK, light, overlay);
        box(pose, mesh, 5, 16.55f, 9.1f, 5.3f, 17.1f, 14.6f, BRASS, light, overlay);
        box(pose, mesh, 10.7f, 16.55f, 9.1f, 11, 17.1f, 14.6f, BRASS, light, overlay);
        for(float x:new float[]{5.65f,10.35f})for(float z:new float[]{9.7f,14f})
            box(pose,mesh,x-.17f,17.1f,z-.17f,x+.17f,17.22f,z+.17f,METAL,light,overlay);
        box(pose, mesh, 6.8f, 17.1f, 10, 9.2f, 17.5f, 12.7f, DARK, light, overlay);
        box(pose, glow, 5.2f, 15.65f, 9.2f, 10.8f, 15.8f, 14.4f, METAL, FULL_BRIGHT, overlay);
        pose.popPose();
        for (int side : new int[] {-1, 1}) {
            pose.pushPose();
            pose.translate(8 + side * 3.65f, 9.2f, LauncherMechanics.DOCK_Z);
            pose.mulPose(Axis.ZP.rotationDegrees(-side * motion.latch() * 58));
            box(pose, mesh, -.45f, 0, -2.5f, .45f, 5.4f, 2.5f, BRASS, light, overlay);
            box(pose, glow, -.47f, 1, -1.6f, .47f, 4.5f, 1.6f, METAL, FULL_BRIGHT, overlay);
            pose.popPose();
        }

        // Two bottom doors hinge outward/down, leaving a real opening for the full-size cell and palm.
        for (int side : new int[] {-1, 1}) {
            pose.pushPose();
            pose.translate(side < 0 ? 4.85f : 11.15f, LauncherMechanics.DOOR_Y, LauncherMechanics.DOCK_Z);
            pose.mulPose(Axis.ZP.rotationDegrees(side * motion.tray() * 95));
            float left = side < 0 ? 0 : -3.15f, right = side < 0 ? 3.15f : 0;
            box(pose, mesh, left, -.65f, -3, right, 0, 3.1f, METAL, light, overlay);
            box(pose, glow, left + .25f, -.72f, -2.3f, right - .25f, -.64f, 2.3f, METAL, FULL_BRIGHT, overlay);
            pose.popPose();
        }
        if (visual.stack().getItem() instanceof DiscCellItem item)
            cell(pose, mesh, luminous, visual.pose().x(), visual.pose().y(), visual.pose().z(), item.tier.color,
                    ChargeableItem.charge(visual.stack()), light, overlay);

        box(pose, glow, 7.2f, 11.3f, 16.1f, 8.8f, 12, 16.25f, METAL, FULL_BRIGHT, overlay);
        float shotAge=LauncherTimeline.shotAge(stack,level,partial);
        if(shotAge>=0 && shotAge<1.2f) {
            var flash=new Tint(luminous,color,210);
            float pulse=1-shotAge/1.2f;
            box(pose,flash,4.9f,8.8f,-.65f-1.55f*pulse,11.1f,9.5f,-.65f,LIGHT,FULL_BRIGHT,overlay);
            box(pose,flash,7.65f,7.8f,-.7f-1.7f*pulse,8.35f,10.5f,-.7f,LIGHT,FULL_BRIGHT,overlay);
        }
        pose.popPose();
    }

    /** Every front part, including the optic, shares one hinge and cannot be left at its idle position. */
    static void frontAssembly(PoseStack pose,VertexConsumer mesh,VertexConsumer glow,float latch,float spin,float boltOffset,int light,int overlay) {
        pose.pushPose();
        pose.translate(8,9,8.2f);pose.mulPose(Axis.XP.rotationDegrees(-latch*14));pose.translate(-8,-9,-8.2f);
        box(pose,mesh,5.7f,12.1f,5.9f,6.3f,16.1f,7.2f,METAL,light,overlay);
        box(pose,mesh,9.7f,12.1f,5.9f,10.3f,16.1f,7.2f,METAL,light,overlay);
        // The complete sight and its solid risers are carried by the hinged front receiver.
        box(pose,mesh,5.7f,16.1f,5.6f,10.3f,17.1f,7.8f,DARK,light,overlay);
        box(pose,mesh,5.65f,17.1f,5.9f,6.1f,20.25f,7.2f,METAL,light,overlay);
        box(pose,mesh,9.9f,17.1f,5.9f,10.35f,20.25f,7.2f,METAL,light,overlay);
        box(pose,mesh,5.65f,20.25f,5.9f,10.35f,20.7f,7.2f,DARK,light,overlay);
        box(pose,mesh,6.1f,17.1f,5.9f,9.9f,17.55f,7.2f,DARK,light,overlay);
        box(pose,glow,5.5f,18.0f,6.15f,5.65f,19.3f,6.85f,LIGHT,FULL_BRIGHT,overlay);
        // Front rail and barrel ribs make the direction legible in both hip fire and ADS.
        for(int i=0;i<4;i++)box(pose,mesh,5.25f,12.1f,1.7f+i*.9f,10.75f,12.5f,2.05f+i*.9f,DARK,light,overlay);

        pose.pushPose();
        pose.translate(0, 0, boltOffset);
        box(pose, mesh, 6, 12, 5.7f, 10, 13.1f, 7.2f, METAL, light, overlay);
        box(pose, mesh, 10, 12, 6, 12.7f, 12.7f, 6.8f, BRASS, light, overlay);
        pose.popPose();
        // Front launch rails break upward, exposing the energized breech behind them.
        box(pose, mesh, 4, 7.6f, 1, 12, 10.8f, 8.3f, DARK, light, overlay);
        box(pose, mesh, 5, 10.8f, 1.8f, 11, 12.1f, 8.2f, METAL, light, overlay);
        box(pose, mesh, 3.7f, 7.3f, -.2f, 12.3f, 11.2f, 1.2f, BRASS, light, overlay);
        box(pose, mesh, 5.2f, 8.25f, -.6f, 10.8f, 10.1f, .2f, DARK, light, overlay);
        for (int z = 2; z < 8; z += 2) {
            box(pose, mesh, 3.65f, 8, z, 4.2f, 10.7f, z + .6f, COPPER, light, overlay);
            box(pose, mesh, 11.8f, 8, z, 12.35f, 10.7f, z + .6f, COPPER, light, overlay);
        }
        box(pose, glow, 5.5f, 10.95f, 2.5f, 6, 11.2f, 7.8f, METAL, FULL_BRIGHT, overlay);
        box(pose, glow, 10, 10.95f, 2.5f, 10.5f, 11.2f, 7.8f, METAL, FULL_BRIGHT, overlay);
        flywheel(pose, mesh, 3.4f, 9.4f, 5.7f, spin, light, overlay);
        flywheel(pose, mesh, 12.6f, 9.4f, 5.7f, -spin, light, overlay);

        pose.popPose();
    }

    /** A distinct physical capacitor cartridge; emitted light is separate from its machined guard frame. */
    public static void cell(PoseStack pose, VertexConsumer mesh, VertexConsumer luminous, float x, float y, float z,
                            int color, float charge, int light, int overlay) {
        pose.pushPose();
        pose.translate(x, y, z);
        float half = LauncherMechanics.CELL_HALF;
        box(pose, mesh, -half, LauncherMechanics.CELL_BOTTOM, -half, half, -2.6f, half, METAL, light, overlay);
        box(pose, mesh, -half, 2.6f, -half, half, 3.6f, half, BRASS, light, overlay);
        box(pose, mesh, -1.5f, 3.6f, -1.5f, 1.5f, LauncherMechanics.CELL_TOP, 1.5f, COPPER, light, overlay);
        for (int a : new int[] {-1, 1}) for (int b : new int[] {-1, 1})
            box(pose, mesh, a * 2.2f - .3f, -2.6f, b * 2.2f - .3f, a * 2.2f + .3f, 2.6f, b * 2.2f + .3f,
                    DARK, light, overlay);
        for (int i = 0; i < 5; i++) {
            int tintColor = charge >= (i + .1f) / 5f ? color : 0x3E5157;
            VertexConsumer core = new Tint(luminous, tintColor, 255);
            float bottom = -2.5f + i * 1.02f;
            box(pose, core, -1.9f, bottom, -1.9f, 1.9f, bottom + .77f, 1.9f, METAL, FULL_BRIGHT, overlay);
        }
        pose.popPose();
    }

    private static void flywheel(PoseStack pose, VertexConsumer out, float cx, float cy, float cz,
                                 float angle, int light, int overlay) {
        pose.pushPose();
        pose.translate(cx,cy,cz);
        pose.mulPose(Axis.XP.rotationDegrees(angle));
        box(pose,out,-.55f,-.55f,-.55f,.55f,.55f,.55f,COPPER,light,overlay);
        for(int i=0;i<8;i++) {
            pose.pushPose();
            pose.mulPose(Axis.XP.rotationDegrees(i*45));
            box(pose,out,-.38f,1.15f,-.46f,.38f,2.25f,.46f,BRASS,light,overlay);
            pose.popPose();
        }
        box(pose,out,-.72f,-.75f,-.32f,.72f,.75f,.32f,METAL,light,overlay);
        pose.popPose();
    }

    /** A thin, twelve-sided model to look like a spinning optical disc rather than a cube. */
    public static void disc(PoseStack pose, VertexConsumer out, float cx, float cy, float cz,
                            float radius, int light, int overlay) {
        pose.pushPose();pose.translate(cx,cy,cz);
        for(int i=0;i<32;i++) {
            double a=i*2*Math.PI/32;
            double b=(i+1)*2*Math.PI/32;
            float ax=(float)Math.cos(a)*radius, az=(float)Math.sin(a)*radius;
            float bx=(float)Math.cos(b)*radius, bz=(float)Math.sin(b)*radius;
            float ix=(float)Math.cos(a)*.45f,iz=(float)Math.sin(a)*.45f,jx=(float)Math.cos(b)*.45f,jz=(float)Math.sin(b)*.45f;
            discFace(pose,out,ix,.04f,iz,jx,.04f,jz,bx,.04f,bz,ax,.04f,az,radius,1,light,overlay);
            discFace(pose,out,ax,-.04f,az,bx,-.04f,bz,jx,-.04f,jz,ix,-.04f,iz,radius,-1,light,overlay);
            quad(pose,out,ax,-.04f,az,ax,.04f,az,bx,.04f,bz,bx,-.04f,bz,(float)Math.cos((a+b)/2),0,(float)Math.sin((a+b)/2),METAL,light,overlay);
        }
        pose.popPose();
    }
    private static void discFace(PoseStack p,VertexConsumer v,float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float radius,float ny,int light,int overlay){
        float u=DISC.u()+.125f,w=DISC.v()+.125f,half=.1171875f;
        vertex(p,v,ax,ay,az,u+ax/radius*half,w+az/radius*half,0xFFFFFF,0,ny,0,light,overlay);
        vertex(p,v,bx,by,bz,u+bx/radius*half,w+bz/radius*half,0xFFFFFF,0,ny,0,light,overlay);
        vertex(p,v,cx,cy,cz,u+cx/radius*half,w+cz/radius*half,0xFFFFFF,0,ny,0,light,overlay);
        vertex(p,v,dx,dy,dz,u+dx/radius*half,w+dz/radius*half,0xFFFFFF,0,ny,0,light,overlay);
    }
    /** Emission is an outer rim, not a second overlapping copy of the entire disc. */
    public static void discGlowRing(PoseStack p,VertexConsumer v,int light,int overlay){
        for(int i=0;i<32;i++){
            double a=i*2*Math.PI/32,b=(i+1)*2*Math.PI/32;float c=(float)Math.cos(a),s=(float)Math.sin(a),cc=(float)Math.cos(b),ss=(float)Math.sin(b);
            quad(p,v,3.64f*c,.045f,3.64f*s,3.83f*c,.045f,3.83f*s,3.83f*cc,.045f,3.83f*ss,3.64f*cc,.045f,3.64f*ss,0,1,0,LIGHT,light,overlay);
            quad(p,v,3.64f*cc,-.045f,3.64f*ss,3.83f*cc,-.045f,3.83f*ss,3.83f*c,-.045f,3.83f*s,3.64f*c,-.045f,3.64f*s,0,-1,0,LIGHT,light,overlay);
        }
    }

}
