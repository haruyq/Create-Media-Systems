package org.haruyq.createmediasystems.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.GlStateBackup;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * A coloured point-light pass over the finished world: it shades the scene's surfaces by their depth and never edits
 * blocks. Features add {@link Source}s; each frame the nearest {@link #MAX_LIGHTS} lights they give are drawn.
 */
public final class PointLights {
    private static final int MAX_LIGHTS = 8;
    /** How far from the camera the entities {@link View#entities()} lists may be, and a block's light should be. */
    public static final double REACH = 10;
    private static final List<Source> SOURCES = new ArrayList<>();
    private static ShaderInstance shader;
    private static TextureTarget scene;

    /** One light this frame: where it is, its colour (0xRRGGBB) and how strong it is. */
    public record Light(Vec3 position, int color, float strength) {}

    /** What a frame's lights are gathered for. */
    public interface View {
        ClientLevel level();
        Camera camera();
        float partial();
        /** The entities within {@link #REACH} of the camera. */
        List<Entity> entities();
    }

    @FunctionalInterface
    public interface Source {
        void collect(View view, Consumer<Light> lights);
    }

    private PointLights() {}

    /** Adds the pass's shader and listeners, once, from the client's setup. */
    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterShadersEvent event) -> {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("cell_light"),
                        DefaultVertexFormat.POSITION), loaded -> shader = loaded);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        NeoForge.EVENT_BUS.addListener(PointLights::render);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (scene != null) { scene.destroyBuffers(); scene = null; }
        });
    }

    public static void add(Source source) {
        SOURCES.add(source);
    }

    private static List<Light> lights(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        List<Entity> nearby = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(camera) <= REACH * REACH) nearby.add(entity);
        }
        View view = new View() {
            @Override public ClientLevel level() { return mc.level; }
            @Override public Camera camera() { return event.getCamera(); }
            @Override public float partial() { return partial; }
            @Override public List<Entity> entities() { return nearby; }
        };
        List<Light> lights = new ArrayList<>();
        Consumer<Light> sink = light -> {
            if (light.strength > 0) lights.add(light);
        };
        SOURCES.forEach(source -> source.collect(view, sink));
        lights.sort(Comparator.comparingDouble(light -> light.position.distanceToSqr(camera)));
        return lights.size() <= MAX_LIGHTS ? lights : lights.subList(0, MAX_LIGHTS);
    }

    private static void render(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || mc.level == null || shader == null || IrisCompat.shadowPass()) return;
        var lights = lights(event);
        if (lights.isEmpty()) return;
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        if (ScreenLight.afterPack(() -> draw(lights, projection, modelView, camera))) return;
        draw(lights, projection, modelView, camera);
    }

    private static void draw(List<Light> lights, Matrix4f projection, Matrix4f modelView, Vec3 camera) {
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
                Light light = lights.get(i);
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
