package org.haruyq.createmediasystems.display;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.playback.NativeAudio;
import org.haruyq.createmediasystems.playback.VlcPlayer;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;

/**
 * An image viewer's picture (see {@link #image}), or a display's: black, with its Blu-ray player's video as it plays here on top, as large as fits uncropped;
 * and, like YouTube's ambient mode, the video's colours glowing on the wall around it (shaders/core/display_glow).
 */
public final class DisplayRenderer implements BlockEntityRenderer<DisplayBlock.Entity> {
    /** How far the picture sits off the panel, and the video off the black, so neither flickers into the other. */
    private static final float LIFT = 0.002f;
    /** How far the glow reaches past each edge, as a share of the display's size; REACH in display_glow.fsh. */
    private static final float GLOW_REACH = 0.3f;
    /** How far the glow sits off the wall behind the display, so it doesn't flicker into it. */
    static final float GLOW_LIFT = 0.01f;
    /** The glow's brightness at a Blu-ray player's 100% lighting. */
    private static final float GLOW_STRENGTH = 0.6f;
    private static ShaderInstance glowShader;
    /** Added to what's behind it, like light; the video's mipmaps are its blur. */
    private static final Function<ResourceLocation, RenderType> GLOW = Util.memoize(texture -> RenderType.create(
            "create_media_systems_display_glow", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> glowShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, true, true))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)));

    /** Everything that draws and sets up displays and image viewers on this computer. */
    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(CreateMediaSystems.DISPLAY_ENTITY.get(), DisplayRenderer::new));
        modBus.addListener(DisplayRenderer::registerShaders);
        DisplayQuillHandler.register();
        Images.register();
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("display_glow"),
                    DefaultVertexFormat.POSITION_TEX_COLOR), shader -> glowShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateMediaSystems.id("display_light"),
                    DefaultVertexFormat.POSITION_TEX_COLOR), DisplayLight::setShader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    DisplayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(DisplayBlock.Entity display, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        Direction facing = display.facing();
        float width = (float) display.width();
        float height = (float) display.height();
        Matrix4f matrix = pose.last().pose();
        DisplayBlock.Screen screen = new DisplayBlock.Screen(facing);
        if (display.isImageViewer()) {
            image(display, buffers, matrix, screen, width, height);
            return;
        }
        panel(buffers.getBuffer(RenderType.textBackground()), matrix, screen, width, height);
        VlcPlayer.Frame frame = frame(display);
        ResourceLocation texture = frame == null ? null : frame.texture();
        float lighting = display.lighting();
        shine(display, texture != null ? lighting * frame.brightness() : 0);
        if (texture == null) {
            return;
        }
        // Under a shader pack, drawn as glowing eyes are: the pack's bloom and emissive lighting take it as a light.
        RenderType picture = IrisCompat.shaderPackInUse() ? RenderType.eyes(texture) : RenderType.text(texture);
        fit(buffers.getBuffer(picture), matrix, screen, width, height, frame.width(), frame.height(), texture, 0);
        if (lighting <= 0) {
            return;
        }
        int glow = Math.round(255 * GLOW_STRENGTH * lighting);
        if (glow > 0) {
            // On the display's back plane: the wall it hangs on. UV 0..1 is the display, the video stretched over it.
            VertexConsumer out = buffers.getBuffer(GLOW.apply(texture));
            float rx = GLOW_REACH * width;
            float ry = GLOW_REACH * height;
            float lift = GLOW_LIFT - (float) DisplayBlock.DEPTH;
            glowVertex(out, matrix, screen.at(-rx, -ry, lift), -GLOW_REACH, 1 + GLOW_REACH, glow);
            glowVertex(out, matrix, screen.at(width + rx, -ry, lift), 1 + GLOW_REACH, 1 + GLOW_REACH, glow);
            glowVertex(out, matrix, screen.at(width + rx, height + ry, lift), 1 + GLOW_REACH, -GLOW_REACH, glow);
            glowVertex(out, matrix, screen.at(-rx, height + ry, lift), -GLOW_REACH, -GLOW_REACH, glow);
        }
        DisplayLight.render(display, screen, width, height, matrix, buffers, texture, DisplayLight.STRENGTH * lighting);
    }

    /** Its image on its black panel, or (without its frame) alone on the wall, see-through where the image is. */
    private static void image(DisplayBlock.Entity viewer, MultiBufferSource buffers, Matrix4f matrix, DisplayBlock.Screen screen,
                              float width, float height) {
        boolean framed = viewer.getBlockState().getValue(ImageViewerBlock.FRAMED);
        if (framed) {
            panel(buffers.getBuffer(RenderType.textBackground()), matrix, screen, width, height);
        }
        Images.Picture picture = picture(viewer);
        if (picture != null) {
            fit(buffers.getBuffer(RenderType.text(picture.texture)), matrix, screen, width, height, picture.width,
                    picture.height, picture.texture, framed ? 0 : -(float) DisplayBlock.DEPTH);
        }
    }

    /**
     * The black panel over the whole screen, its edges reaching back to the wall so it sits on it rather than floating
     * off it past the block's own model; a hair wider than the model, so as not to flicker into its sides.
     */
    private static void panel(VertexConsumer out, Matrix4f matrix, DisplayBlock.Screen screen, float width, float height) {
        quad(out, matrix, screen, 0, 0, width, height, LIFT, null);
        float back = -(float) DisplayBlock.DEPTH;
        float x0 = -LIFT, y0 = -LIFT, x1 = width + LIFT, y1 = height + LIFT;
        side(out, matrix, screen.at(x0, y0, LIFT), screen.at(x0, y0, back), screen.at(x1, y0, back), screen.at(x1, y0, LIFT));
        side(out, matrix, screen.at(x0, y1, LIFT), screen.at(x1, y1, LIFT), screen.at(x1, y1, back), screen.at(x0, y1, back));
        side(out, matrix, screen.at(x0, y0, LIFT), screen.at(x0, y1, LIFT), screen.at(x0, y1, back), screen.at(x0, y0, back));
        side(out, matrix, screen.at(x1, y0, LIFT), screen.at(x1, y0, back), screen.at(x1, y1, back), screen.at(x1, y1, LIFT));
    }

    /** A black quad through four corners, counter-clockwise as seen from the side it faces. */
    private static void side(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        for (Vec3 at : new Vec3[] {a, b, c, d}) {
            vertex(out, matrix, at, 0, 0, Vec3.ZERO, null);
        }
    }

    /** A picture {@code pw} × {@code ph} pixels, as large as fits uncropped in the middle of the screen, {@code base} off its front. */
    private static void fit(VertexConsumer out, Matrix4f matrix, DisplayBlock.Screen screen, float width, float height, int pw,
                            int ph, ResourceLocation texture, float base) {
        double scale = Math.min((double) width / pw, (double) height / ph);
        float w = (float) (pw * scale);
        float h = (float) (ph * scale);
        float x = (width - w) / 2;
        float y = (height - h) / 2;
        quad(out, matrix, screen, x, y, x + w, y + h, base + 2 * LIFT, texture);
    }

    private static void glowVertex(VertexConsumer out, Matrix4f matrix, Vec3 at, float u, float v, int alpha) {
        out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setUv(u, v).setColor(255, 255, 255, alpha);
    }

    /** The rectangle from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) on the screen, in blocks. */
    private static void quad(VertexConsumer out, Matrix4f matrix, DisplayBlock.Screen screen, float x0, float y0, float x1,
                             float y1, float lift, ResourceLocation texture) {
        vertex(out, matrix, screen.at(x0, y0, lift), 0, 1, screen.out(), texture);
        vertex(out, matrix, screen.at(x1, y0, lift), 1, 1, screen.out(), texture);
        vertex(out, matrix, screen.at(x1, y1, lift), 1, 0, screen.out(), texture);
        vertex(out, matrix, screen.at(x0, y1, lift), 0, 0, screen.out(), texture);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 at, float u, float v, Vec3 normal,
                               ResourceLocation texture) {
        if (texture == null) {
            out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(0, 0, 0, 255)
                    .setLight(LightTexture.FULL_BRIGHT);
        } else {
            // Overlay and normal for eyes (see render); text has neither, and leaves them out.
            out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(255, 255, 255, 255)
                    .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                    .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
        }
    }

    /**
     * Makes the display a light source as bright as its picture ({@code brightness} 0..1), on this computer only, a few
     * times a second: each change relights the blocks around it.
     */
    private static void shine(DisplayBlock.Entity display, float brightness) {
        Level level = display.getLevel();
        // Not in Ponder's scenes: their worlds have no light to change.
        if (level != Minecraft.getInstance().level || level.getGameTime() % 4 != 0) {
            return;
        }
        AuxiliaryLightManager lights = level.getAuxLightManager(display.getBlockPos());
        if (lights != null) {
            lights.setLightAt(display.getBlockPos(), Math.round(15 * (float) Math.sqrt(brightness)));
        }
    }

    /** The video a display shows as it plays here, or null. */
    @Nullable
    static VlcPlayer.Frame frame(DisplayBlock.Entity display) {
        return display.source().map(NativeAudio::screen).orElse(null);
    }

    /** The picture an image viewer shows, or null while it has none (or it isn't loaded yet). */
    @Nullable
    static Images.Picture picture(DisplayBlock.Entity viewer) {
        return viewer.image().isEmpty() ? null : Images.get(viewer.image());
    }

    @Override
    public boolean shouldRenderOffScreen(DisplayBlock.Entity display) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(DisplayBlock.Entity display) {
        float r = Math.max(DisplayLight.RANGE, GLOW_REACH * (float) Math.max(display.width(), display.height()));
        return display.box(r, DisplayLight.RANGE).inflate(0.1);
    }
}
