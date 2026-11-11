package org.haruyq.createmediasystems.launcher.client;

import org.haruyq.createmediasystems.power.ChargeableItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.launcher.LauncherFeature;
import org.haruyq.createmediasystems.client.IrisCompat;
import org.haruyq.createmediasystems.client.ScreenLight;
import org.haruyq.createmediasystems.launcher.CellLighting;
import org.haruyq.createmediasystems.launcher.DiscCellCharger;
import org.haruyq.createmediasystems.launcher.DiscCellItem;
import org.haruyq.createmediasystems.launcher.LauncherMechanics;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.GlStateBackup;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Own RGB point-light pass: shades existing scene surfaces using depth, never edits world blocks. */
public final class CellGlowRenderer {
    private static final int MAX_LIGHTS = 8;
    private static final Map<BlockPos, Dock> DOCKS = new HashMap<>();
    private static ShaderInstance shader;
    private static TextureTarget scene;
    private record Source(Vec3 position, int color, float strength) {}
    private record Dock(DiscCellCharger charger, long tick) {}

    private CellGlowRenderer() {}

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterShadersEvent event) -> {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("cell_light"),
                        DefaultVertexFormat.POSITION), loaded -> shader = loaded);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        NeoForge.EVENT_BUS.addListener(CellGlowRenderer::render);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            DOCKS.clear();
            if (scene != null) { scene.destroyBuffers(); scene = null; }
        });
    }

    public static void track(DiscCellCharger charger) {
        if (charger.getLevel() != null) DOCKS.put(charger.getBlockPos(), new Dock(charger, charger.getLevel().getGameTime()));
    }

    private static void addCell(List<Source> sources, ItemStack cell, Vec3 position, float exposure) {
        if (!(cell.getItem() instanceof DiscCellItem item)) return;
        float strength = CellLighting.strength(ChargeableItem.charge(cell));
        if (strength > 0) sources.add(new Source(position, item.tier.color, strength * exposure));
    }

    private static List<Source> sources(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        List<Source> sources = new ArrayList<>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(camera) > 100) continue;
            if (entity instanceof org.haruyq.createmediasystems.partybox.PartyBoxEntity box) {
                if(box.playing() && box.energy()>0 && box.lights()!=0)
                    sources.add(new Source(box.soundPosition().add(Vec3.directionFromRotation(0,box.getYRot()+180).scale(.25)),org.haruyq.createmediasystems.partybox.client.PartyBoxMesh.lightColor(
                            (mc.level.getGameTime()+(double)partial)/20.0,box.lights()),.6f));
            } else if (entity instanceof Player player) {
                ItemStack gun = player.getMainHandItem();
                if (gun.is(LauncherFeature.DISC_LAUNCHER.get())) {
                    var visual = LauncherClient.cellVisual(gun, mc.level, partial);
                    if (visual.strength() <= 0) continue;
                    var motion = LauncherClient.frame(gun, mc.level, partial);
                    Vec3 position;
                    if (player == mc.player && mc.options.getCameraType().isFirstPerson()) {
                        PoseStack local = new PoseStack();
                        float hand = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
                        float bob = (float) Math.sin((player.tickCount + partial) * .055f) * .0025f;
                        LauncherClient.weaponPose(local, motion, hand, 0, bob);
                        local.translate(-.5f, -.5f, -.5f);
                        var path = visual.pose();
                        Vector3f point = local.last().pose().transformPosition(new Vector3f(path.x() / 16, path.y() / 16, path.z() / 16));
                        point.rotate(event.getCamera().rotation());
                        position = camera.add(point.x, point.y, point.z);
                    } else {
                        position = player.getEyePosition(partial).add(player.getViewVector(partial).scale(.4))
                                .add(0, -.35 + (visual.pose().y() - LauncherMechanics.DOCK_Y) / 24, 0);
                    }
                    sources.add(new Source(position, visual.color(), visual.strength() * (.55f + motion.latch() * 1.15f)));
                } else {
                    addCell(sources, gun, player.getEyePosition(partial).add(player.getViewVector(partial).scale(.4)).add(0, -.5, 0), .8f);
                }
                addCell(sources, player.getOffhandItem(), player.getEyePosition(partial).add(0, -.6, 0), .7f);
            } else if (entity instanceof ItemEntity item) addCell(sources, item.getItem(), entity.getPosition(partial).add(0, .15, 0), .7f);
        }
        long now = mc.level.getGameTime();
        DOCKS.values().removeIf(dock -> dock.charger.isRemoved() || dock.charger.getLevel() != mc.level || now - dock.tick > 2);
        for (var dock : DOCKS.values()) {
            Vec3 position = Vec3.atCenterOf(dock.charger.getBlockPos()).add(0, .06, 0);
            if (position.distanceToSqr(camera) < 100) addCell(sources, dock.charger.cell(), position, 1.1f);
        }
        sources.sort(Comparator.comparingDouble(source -> source.position.distanceToSqr(camera)));
        return sources.size() <= MAX_LIGHTS ? sources : sources.subList(0, MAX_LIGHTS);
    }

    private static void render(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || mc.level == null || shader == null || IrisCompat.shadowPass()) return;
        var lights = sources(event);
        if (lights.isEmpty()) return;
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        if (ScreenLight.afterPack(() -> draw(lights, projection, modelView, camera))) return;
        draw(lights, projection, modelView, camera);
    }

    private static void draw(List<Source> lights, Matrix4f projection, Matrix4f modelView, Vec3 camera) {
        var mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        if (main.getDepthTextureId() < 0) return;
        // Depth blits require matching formats. Other renderers can enable packed depth/stencil on the main target.
        if (scene == null || scene.isStencilEnabled() != main.isStencilEnabled()) {
            if (scene != null) scene.destroyBuffers();
            scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            if (main.isStencilEnabled()) scene.enableStencil();
        } else if (scene.width != main.width || scene.height != main.height) {
            scene.resize(main.width, main.height, Minecraft.ON_OSX);
        }

        // Separate color/depth textures avoid sampling the framebuffer being written to.
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(true);

        GlStateBackup state = new GlStateBackup();
        RenderSystem.backupGlState(state);
        ShaderInstance previous = RenderSystem.getShader();
        try {
            RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
            RenderSystem.disableBlend(); RenderSystem.disableCull();
            shader.setSampler("Scene", scene.getColorTextureId());
            shader.setSampler("Depth", scene.getDepthTextureId());
            shader.safeGetUniform("Viewport").set((float) main.width, (float) main.height);
            shader.safeGetUniform("InverseProjection").set(new Matrix4f(projection).invert());
            shader.safeGetUniform("CellProjection").set(projection);
            shader.safeGetUniform("LightCount").set(lights.size());
            for (int i = 0; i < MAX_LIGHTS; i++) {
                if (i >= lights.size()) {
                    shader.safeGetUniform("Source" + i).set(0f, 0f, 0f, 0f);
                    shader.safeGetUniform("Tint" + i).set(0f, 0f, 0f, 0f);
                    continue;
                }
                Source light = lights.get(i);
                Vec3 relative = light.position.subtract(camera);
                Vector3f view = modelView.transformPosition(new Vector3f((float) relative.x, (float) relative.y, (float) relative.z));
                shader.safeGetUniform("Source" + i).set(view.x, view.y, view.z, 2.8f);
                shader.safeGetUniform("Tint" + i).set((light.color >> 16 & 255) / 255f,
                        (light.color >> 8 & 255) / 255f, (light.color & 255) / 255f, light.strength);
            }
            RenderSystem.setShader(() -> shader);
            var quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            quad.addVertex(-1, -1, 0); quad.addVertex(1, -1, 0); quad.addVertex(1, 1, 0); quad.addVertex(-1, 1, 0);
            BufferUploader.drawWithShader(quad.buildOrThrow());
        } finally {
            RenderSystem.setShader(() -> previous);
            RenderSystem.restoreGlState(state);
        }
    }
}
