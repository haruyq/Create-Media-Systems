package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.launcher.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/** A compact angled weapon card: original vector weapon art, actual installed rounds, capacity and spare cells. */
public final class LauncherHud {
    private static final ResourceLocation LAYER=CreateMediaSystems.id("launcher_hud");
    private static final Style FONT=Style.EMPTY.withFont(CreateMediaSystems.id("hud"));
    private static final int WHITE=0xFFF4F5EB,MUTED=0xFFB7BEAE,AMBER=0xFFFFC66B,RED=0xFFFF6157;
    private static final int WIDTH=318,HEIGHT=88;
    private static int spareCells,cachedTick=Integer.MIN_VALUE;
    private static Object cachedPlayer;
    public static boolean reticleDrawn;
    private LauncherHud() {}
    public static void register(IEventBus bus) {
        bus.addListener((RegisterGuiLayersEvent event)->event.registerAbove(VanillaGuiLayers.CROSSHAIR,LAYER,LauncherHud::render));
        NeoForge.EVENT_BUS.addListener(LauncherHud::hideVanillaCrosshair);
    }
    private static boolean visible(Minecraft mc) {
        return mc.player!=null && mc.level!=null && mc.screen==null && !mc.options.hideGui && !mc.player.isSpectator()
                && mc.player.getMainHandItem().is(LauncherFeature.DISC_LAUNCHER.get());
    }
    private static void hideVanillaCrosshair(RenderGuiLayerEvent.Pre e) {
        var mc=Minecraft.getInstance();if(e.getName().equals(VanillaGuiLayers.CROSSHAIR) && visible(mc) && mc.options.getCameraType().isFirstPerson())e.setCanceled(true);
    }
    private static void render(GuiGraphics g,DeltaTracker delta) {
        reticleDrawn=false;var mc=Minecraft.getInstance();if(!visible(mc))return;
        var gun=mc.player.getMainHandItem();var r=LauncherReadout.of(gun);var tier=r.tier();
        boolean reload=LauncherMagazine.reloading(gun),eject=LauncherMagazine.ejecting(gun),low=r.shots()<=Math.max(3,r.capacity()/5);
        int accent=r.hasCell()?0xFF000000|tier.color:MUTED;
        int status=reload?AMBER:!r.hasOriginal()||r.shots()==0||low?RED:accent;
        float partial=delta.getGameTimeDeltaPartialTick(true),aim=LauncherClient.aim(partial);
        float age=reload?LauncherTimeline.reloadAge(gun,mc.level,partial):0;
        float progress=Math.clamp(age/LauncherTimeline.RELOAD_TICKS,0,1);
        refreshReserves(mc);
        float pixels=Math.clamp(mc.getWindow().getHeight()/760f,.72f,2.5f),unit=pixels/(float)mc.getWindow().getGuiScale();
        float freeSide=mc.getWindow().getWidth()/2f-92*(float)mc.getWindow().getGuiScale();
        float bottom=freeSide>(WIDTH+12)*pixels?15:(float)mc.getWindow().getGuiScale()*24/pixels+9;
        g.pose().pushPose();g.pose().translate(g.guiWidth()-(WIDTH+12)*unit,g.guiHeight()-(HEIGHT+bottom)*unit,0);g.pose().scale(unit,unit,1);
        polygon(g,0x8D05080B,18,0,WIDTH,0,WIDTH,23,WIDTH-19,68,8,68,0,52);
        polygon(g,0x58222A2A,19,3,WIDTH-3,3,WIDTH-3,22,WIDTH-22,65,10,65,3,51);
        line(g,18,1,WIDTH,1,1,0xB6D5DCCB);line(g,18,1,1,51,1,0xB6D5DCCB);
        line(g,1,51,9,67,1,0xB6D5DCCB);line(g,9,67,WIDTH-20,67,1,0xB6D5DCCB);
        line(g,WIDTH-20,67,WIDTH,22,1,0xB6D5DCCB);
        gunIcon(g,20,10,0xFFE5E9DF);
        // Four compact attachment/state sockets echo the supplied layout, with original shapes and live cell state.
        socket(g,14,48,24,aim>.85?accent:0xFF78837A,"ADS");
        socket(g,41,48,20,r.hasOriginal()?0xFFD5CB86:RED,"CD");
        socket(g,64,48,20,accent,r.hasCell()?"L"+tier.level:"--");
        socket(g,87,48,37,r.shots()>0?accent:RED,Math.round(r.charge()*100)+"%");
        String rounds=r.hasCell()?Integer.toString(r.shots()):"--";
        right(g,Component.literal(rounds),238,0,3.45f,!r.hasCell()?MUTED:low?RED:WHITE);
        label(g,"/ "+r.capacity(),211,39,1.2f,MUTED);
        // Cell-shaped badge has a clear level, instead of an unrelated fixed 512-round symbol.
        polygon(g,0xA53C443A,262,6,WIDTH-8,6,WIDTH-5,22,WIDTH-23,60,265,60,250,23);
        polygon(g,accent,266,10,WIDTH-11,10,WIDTH-10,22,WIDTH-26,56,268,56,255,23);
        polygon(g,0xDB172019,269,14,WIDTH-15,14,WIDTH-14,23,WIDTH-29,51,271,51,260,23);
        right(g,Component.literal(r.hasCell()?Integer.toString(tier.level):"-"),292,14,2.6f,WHITE);
        label(g,"LV",272,42,.65f,accent);
        for(int n=0;n<3;n++)polygon(g,0xFFD0D6BE,151+n*5,44,154+n*5,41,154+n*5,49,151+n*5,47);
        label(g,Integer.toString(spareCells),174,39,1.4f,WHITE);label(g,"CELLS",174,54,.58f,MUTED);
        polygon(g,0xBA0C1114,9,71,WIDTH-20,71,WIDTH-29,HEIGHT,16,HEIGHT,4,77);
        polygon(g,accent,151,71,WIDTH-20,71,WIDTH-29,HEIGHT,142,HEIGHT);
        label(g,"DISC LAUNCHER",16,72,.93f,WHITE);
        String cellName=r.hasCell()?tier.id.toUpperCase(java.util.Locale.ROOT):"NO CELL";
        right(g,Component.literal(cellName),WIDTH-39,74,.77f,0xFF10191A);
        if(reload) {
            var incoming=LauncherMagazine.pending(gun);
            String next=incoming.getItem() instanceof DiscCellItem cell && !eject?" > LV "+cell.tier.level:"";
            label(g,(eject?"EJECT":"RELOAD")+String.format(java.util.Locale.ROOT,"  %.1fs",Math.max(0,(LauncherTimeline.RELOAD_TICKS-age)/20))+next,18,-17,.9f,AMBER);
            rect(g,17,64,WIDTH-24,66,0x5C8B9488);rect(g,17,64,17+(WIDTH-41)*progress,66,AMBER);
        } else if(!r.hasCell()||!r.hasOriginal()||r.shots()==0||low) {
            label(g,tr(!r.hasCell()?"no_cell":!r.hasOriginal()?"no_original":r.shots()==0?"depleted":"low"),18,-17,.80f,status);
        }
        g.pose().popPose();
        if(mc.options.getCameraType().isFirstPerson() && !mc.getDebugOverlay().showDebugScreen()) {
            reticle(g,mc,unit,aim,reload,progress);damageNumbers(g,mc,unit,partial);
        }
    }
    private static void socket(GuiGraphics g,float x,float y,float width,int color,String text) {
        polygon(g,0xCF0A1011,x+2,y,x+width,y,x+width,y+15,x,y+15,x,y+2);
        line(g,x+2,y,x+width,y,1,color);line(g,x+width,y,x+width,y+15,1,color);
        label(g,text,x+3,y+1,.75f,color);
    }
    private static void gunIcon(GuiGraphics g,float x,float y,int color) {
        g.pose().pushPose();g.pose().translate(x,y,0);
        polygon(g,color,1,17,33,17,33,12,49,12,49,8,95,8,95,11,119,11,119,16,141,16,145,21,137,28,111,28,105,34,96,34,96,26,88,26,85,39,76,39,77,26,32,26,32,23,1,23);
        rect(g,50,4,73,8,color);rect(g,56,0,68,4,color);rect(g,6,14,11,26,color);
        for(int n=0;n<5;n++)rect(g,16+n*5,16,18+n*5,24,0xFF1C2427);
        rect(g,47,14,71,17,0xFF434C4A);rect(g,100,15,117,19,0xFF4E5752);
        rect(g,79,28,83,36,0xFF535D55);line(g,119,25,134,25,1,0xFF5F695F);
        arc(g,87,17,6,0,360,1.5f,0xFF535E55);arc(g,87,17,3,0,360,1,0xFFD2D8CA);
        g.pose().popPose();
    }
    private static void refreshReserves(Minecraft mc) {
        if(cachedPlayer==mc.player && cachedTick==mc.player.tickCount)return;
        cachedPlayer=mc.player;cachedTick=mc.player.tickCount;spareCells=0;
        for(int i=0;i<mc.player.getInventory().getContainerSize();i++) {
            ItemStack cell=mc.player.getInventory().getItem(i);
            if(cell.getItem() instanceof DiscCellItem && DiscCellItem.hasMaster(cell) && DiscCellItem.shots(cell)>0)spareCells+=cell.getCount();
        }
    }
    private static void reticle(GuiGraphics g,Minecraft mc,float unit,float aim,boolean reload,float progress) {
        if(reload)return;
        reticleDrawn=true;
        g.pose().pushPose();g.pose().translate(g.guiWidth()*.5f,g.guiHeight()*.5f,0);g.pose().scale(unit,unit,1);
        float movement=(float)Math.min(1,mc.player.getDeltaMovement().horizontalDistance()*4);
        float gap=5+movement*3+LauncherClient.kick(1)*3;
        int alpha=Math.round(240*(1-aim));
        if(alpha>6)for(int a:new int[]{-1,1}) {
            line(g,a*gap,0,a*(gap+5),0,1.4f,(alpha<<24)|0xF0F2EA);
            line(g,0,a*gap,0,a*(gap+5),1.4f,(alpha<<24)|0xF0F2EA);
        }
        int dot=aim>.7?0xFFFF5149:WHITE;
        polygon(g,0xA0000000,0,-2.5f,2.5f,0,0,2.5f,-2.5f,0);
        polygon(g,dot,0,-1.1f,1.1f,0,0,1.1f,-1.1f,0);
        float age=LauncherClient.hitAge();
        if(age>=0 && age<.30f && LauncherClient.CROSSHAIR_FEEDBACK.get()!=LauncherDamageDisplay.Crosshair.OFF) {
            int tint=LauncherClient.killed()?0xFF635A:LauncherClient.headHit()?0xFFD667:0xF7F8EE;
            int color=(Math.round(255*Math.min(1,(.30f-age)/.12f))<<24)|tint;
            float begin=5+Math.max(0,.06f-age)*18,end=11+Math.max(0,.06f-age)*18;
            for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}) {
                line(g,a*begin,b*begin,a*end,b*end,3,0xA0000000);
                line(g,a*begin,b*begin,a*end,b*end,1.5f,color);
            }
            var entry=LauncherClient.damageDisplay().latest();
            if(entry!=null && entry.shield()>0 && LauncherClient.CROSSHAIR_FEEDBACK.get()==LauncherDamageDisplay.Crosshair.X_WITH_SHIELD)
                shield(g,19,0,entry.shieldBroken()?RED:0xFF66C6FF,entry.shieldBroken());
        }
        g.pose().popPose();
    }

    private static void shield(GuiGraphics g,float x,float y,int color,boolean broken) {
        line(g,x-4,y-4,x+4,y-4,1,color);line(g,x-4,y-4,x-4,y,1,color);line(g,x+4,y-4,x+4,y,1,color);
        line(g,x-4,y,x,y+5,1,color);line(g,x+4,y,x,y+5,1,color);
        if(broken){line(g,x+2,y-5,x-2,y+1,1,RED);line(g,x-2,y+1,x+2,y+6,1,RED);}
    }
    private static float[] project(Minecraft mc,GuiGraphics g,double x,double y,double z,float partial) {
        var camera=mc.gameRenderer.getMainCamera();var at=camera.getPosition();
        var v=new org.joml.Vector3f((float)(x-at.x),(float)(y-at.y),(float)(z-at.z));
        v.rotate(new org.joml.Quaternionf(camera.rotation()).conjugate());
        double fov=((org.haruyq.createmediasystems.mixin.GameRendererAccessor)mc.gameRenderer).cms$fieldOfView(camera,partial,true);
        var clip=mc.gameRenderer.getProjectionMatrix(fov).transform(new org.joml.Vector4f(v,1));
        if(clip.w<=.05f)return null;
        float px=(clip.x/clip.w+1)*.5f*g.guiWidth(),py=(1-clip.y/clip.w)*.5f*g.guiHeight();
        return px<0||px>g.guiWidth()||py<0||py>g.guiHeight()?null:new float[]{px,py};
    }
    private static int damageColor(LauncherDamageDisplay.Entry e,int alpha) {
        return alpha<<24 | (e.head()?0xFFD468:e.shield()>0?0x69BEFF:0xFF756A);
    }
    private static void outlined(GuiGraphics g,String value,float x,float y,float size,int color) {
        float offset=.65f;
        for(int a:new int[]{-1,1})for(int b:new int[]{-1,1})label(g,value,x+a*offset,y+b*offset,size,(color&0xFF000000)|0x101318);
        label(g,value,x,y,size,color);
    }
    private static void damageNumbers(GuiGraphics g,Minecraft mc,float unit,float partial) {
        var mode=LauncherClient.DAMAGE_NUMBERS.get();if(mode==LauncherDamageDisplay.Mode.OFF)return;
        var display=LauncherClient.damageDisplay();double now=System.nanoTime()/1_000_000_000.0;
        if((mode==LauncherDamageDisplay.Mode.STACKING || mode==LauncherDamageDisplay.Mode.BOTH) && display.visible(now)) {
            var e=display.latest();var entity=mc.level.getEntity(e.target());
            double x=entity==null?e.x():entity.getX(),y=entity==null?e.y()+.55:entity.getBoundingBox().maxY+.27,z=entity==null?e.z():entity.getZ();
            var at=project(mc,g,x,y,z,partial);
            if(at!=null) {
                float age=(float)display.age(now),size=1.8f+.18f*(float)Math.exp(-age*26);
                int alpha=Math.round(255*Math.min(1,(1-age)/.2f));String number=Integer.toString(Math.round(display.total()));
                float width=mc.font.width(Component.literal(number).withStyle(FONT))*size;
                g.pose().pushPose();g.pose().translate(at[0],at[1],0);g.pose().scale(unit,unit,1);
                outlined(g,number,-width/2,-17-(float)Math.exp(-age*25)*2,size,damageColor(e,alpha));
                if(e.head())label(g,"HEADSHOT",-18,4,.67f,damageColor(e,alpha));
                if(e.shield()>0)shield(g,-width/2-10,-8,e.shieldBroken()?RED:0xFF66C6FF,e.shieldBroken());
                g.pose().popPose();
            }
        }
        if(mode==LauncherDamageDisplay.Mode.FLOATING || mode==LauncherDamageDisplay.Mode.BOTH) {
            int index=0;
            for(var e:display.floating(now)) {
                double age=now-e.at();var at=project(mc,g,e.x(),e.y()+.15+age*.7,e.z(),partial);
                if(at==null)continue;
                int alpha=(int)Math.clamp((LauncherDamageDisplay.FLOAT_SECONDS-age)*650,0,255);
                String number=Integer.toString(Math.round(e.damage()));
                g.pose().pushPose();g.pose().translate(at[0],at[1],0);g.pose().scale(unit,unit,1);
                outlined(g,number,12+(index%3)*10,(float)-age*12,1.2f,damageColor(e,alpha));g.pose().popPose();index++;
            }
        }
    }

    private static Component tr(String key) { return Component.translatable("hud.create_media_systems." + key); }
    private static void label(GuiGraphics gui, String text, float x, float y, float size, int color) {
        label(gui, Component.literal(text), x, y, size, color);
    }
    private static void label(GuiGraphics gui, Component text, float x, float y, float size, int color) {
        gui.pose().pushPose(); gui.pose().translate(x, y, 0); gui.pose().scale(size, size, 1);
        gui.drawString(Minecraft.getInstance().font, text.copy().withStyle(FONT), 0, 0, color, false);
        gui.pose().popPose();
    }
    private static void right(GuiGraphics gui, Component text, float x, float y, float size, int color) {
        label(gui, text, x - Minecraft.getInstance().font.width(text.copy().withStyle(FONT)) * size, y, size, color);
    }
    private static void rect(GuiGraphics gui, float x1, float y1, float x2, float y2, int color) {
        polygon(gui, color, x1, y1, x2, y1, x2, y2, x1, y2);
    }
    private static void line(GuiGraphics gui, float x1, float y1, float x2, float y2, float width, int color) {
        float length = (float) Math.hypot(x2 - x1, y2 - y1);
        if (length < .001f) return;
        float dx = (y2 - y1) / length * width / 2, dy = (x2 - x1) / length * width / 2;
        polygon(gui, color, x1 - dx, y1 + dy, x1 + dx, y1 - dy, x2 + dx, y2 - dy, x2 - dx, y2 + dy);
    }
    private static void arc(GuiGraphics gui, float x, float y, float radius, float from, float to, float width, int color) {
        int steps = Math.max(1, (int) Math.ceil(Math.abs(to - from) / 9));
        float a = (float) Math.toRadians(from);
        for (int i = 1; i <= steps; i++) {
            float b = (float) Math.toRadians(from + (to - from) * i / steps);
            line(gui, x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius,
                    x + (float) Math.cos(b) * radius, y + (float) Math.sin(b) * radius, width, color);
            a = b;
        }
    }
    private static void polygon(GuiGraphics gui, int color, float... points) {
        // Acquire per primitive: font/texture drawing may finish the previous unfixed GUI buffer.
        VertexConsumer vertices = gui.bufferSource().getBuffer(RenderType.gui());
        var matrix = gui.pose().last().pose();
        for (int i = 2; i + 3 < points.length; i += 2) {
            vertices.addVertex(matrix, points[0], points[1], 0).setColor(color);
            vertices.addVertex(matrix, points[i + 2], points[i + 3], 0).setColor(color);
            vertices.addVertex(matrix, points[i], points[i + 1], 0).setColor(color);
            vertices.addVertex(matrix, points[i], points[i + 1], 0).setColor(color);
        }
    }
}
