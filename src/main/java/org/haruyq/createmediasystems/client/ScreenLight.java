package org.haruyq.createmediasystems.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Light added over the world without writing depth: stage lights' beams and the light they and displays cast on
 * surfaces. Under a shader pack, displays draw their pictures again over the pack's finished frame
 * (DisplayRenderer.overdraw); such light is added after that, over all of the frame alike, so it neither goes under a
 * picture nor looks different over one than beside it.
 */
public final class ScreenLight {
    private static final List<Runnable> AFTER_PACK = new ArrayList<>();

    private ScreenLight() {
    }

    /** Adds its listener, once, from the client's setup. */
    public static void register() {
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

    /**
     * Under a shader pack, {@code draw} runs once the pack has finished the frame and displays have their pictures
     * back, and true is returned; otherwise false, for the caller to draw it now.
     */
    public static boolean afterPack(Runnable draw) {
        if (!IrisCompat.shaderPackInUse() || IrisCompat.shadowPass()) {
            return false;
        }
        AFTER_PACK.add(draw);
        return true;
    }
}
