package org.haruyq.createmediasystems.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Light added over the world without writing depth, as stage lights' beams are. Under a shader pack, displays draw
 * their pictures again over the pack's finished frame (DisplayRenderer.overdraw), which would cover such light in
 * front of them: it is kept a second time here, and added back onto the pictures.
 */
public final class ScreenLight {
    private static final Map<RenderType, ByteBufferBuilder> MEMORY = new HashMap<>();
    /** This frame's light, by render type. */
    private static final Map<RenderType, BufferBuilder> KEPT = new LinkedHashMap<>();
    private static final List<Runnable> DRAW = new ArrayList<>();
    private static final List<Runnable> AFTER_PACK = new ArrayList<>();
    /** The light alone, over black. */
    @Nullable
    private static TextureTarget target;

    private ScreenLight() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && !AFTER_PACK.isEmpty()) {
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
                try {
                    AFTER_PACK.forEach(Runnable::run);
                } finally {
                    AFTER_PACK.clear();
                }
            }
        });
    }

    /** Surface illumination is added after Iris lights the world and displays restore their pictures. */
    public static boolean afterPack(Runnable draw) {
        if (!IrisCompat.shaderPackInUse() || IrisCompat.shadowPass()) {
            return false;
        }
        AFTER_PACK.add(draw);
        return true;
    }

    /**
     * {@code out}, and under a shader pack the same vertices kept for displays' pictures. {@code type} must add to
     * what's behind it (RenderStateShard.LIGHTNING_TRANSPARENCY), as light does, and be drawn this frame.
     */
    public static VertexConsumer alsoOnScreens(RenderType type, VertexConsumer out) {
        if (!IrisCompat.shaderPackInUse() || IrisCompat.shadowPass()) {
            return out;
        }
        BufferBuilder kept = KEPT.computeIfAbsent(type, t -> new BufferBuilder(
                MEMORY.computeIfAbsent(t, m -> new ByteBufferBuilder(t.bufferSize())), t.mode(), t.format()));
        return VertexMultiConsumer.create(out, kept);
    }

    public static void alsoOnScreens(Runnable draw) {
        if (IrisCompat.shaderPackInUse() && !IrisCompat.shadowPass()) {
            DRAW.add(draw);
        }
    }

    /**
     * Draws this frame's light alone over black, tested against the world's depth so what stands in front of it
     * still hides it, under the frame's camera; returns that picture's texture, then the main target is bound again.
     */
    static int draw() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            target = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        target.setClearColor(0, 0, 0, 0);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        // Attached anew each frame: the main target's depth texture changes with the window (and with Iris).
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
                main.getDepthTextureId(), 0);
        KEPT.forEach((type, kept) -> {
            MeshData mesh = kept.build();
            if (mesh != null) {
                type.draw(mesh);
            }
        });
        KEPT.clear();
        DRAW.forEach(Runnable::run);
        DRAW.clear();
        main.bindWrite(true);
        return target.getColorTextureId();
    }

    /** No display drew its picture again this frame: this frame's light is dropped. */
    static void discard() {
        KEPT.values().forEach(kept -> {
            MeshData mesh = kept.build();
            if (mesh != null) {
                mesh.close();
            }
        });
        KEPT.clear();
        DRAW.clear();
    }
}
