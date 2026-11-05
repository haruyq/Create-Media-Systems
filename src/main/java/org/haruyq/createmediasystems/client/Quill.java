package org.haruyq.createmediasystems.client;

import java.util.function.BooleanSupplier;
import java.util.function.DoublePredicate;

import org.haruyq.createmediasystems.Reach;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.utility.RaycastHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/** What the range quill and the display quill work alike: when, with which input, on what, and what they say. */
public final class Quill {
    /** Outline colour of what is being set up: the range quill's selection, a display linked to the picked player. */
    public static final int SETTING = 0x5cc8b0;
    /** Outline colour of what is picked, or set up some other way: the picked source, a display not linked to it. */
    public static final int PICKED = 0xf0c040;
    public static final float LINE_WIDTH = 1 / 16f;

    private Quill() {
    }

    /**
     * Runs {@code tick} every client tick, and hands the mouse wheel to {@code scroll} and right-clicks to
     * {@code rightClick}; either returns true to keep that input from the game.
     */
    public static void listen(Runnable tick, DoublePredicate scroll, BooleanSupplier rightClick) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick.run());
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent event) -> {
            if (scroll.test(event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> {
            if (event.getButton() == 1 && event.getAction() != 0 && rightClick.getAsBoolean()) {
                event.setCanceled(true);
            }
        });
    }

    /** Whether the player holds {@code quill} in their main hand, with no screen open. */
    public static boolean held(Item quill) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && mc.screen == null && mc.player.getMainHandItem().is(quill);
    }

    /** The block the player points at within a quill's reach, or null. */
    @Nullable
    public static BlockHitResult pointedAt() {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult trace = RaycastHelper.rayTraceRange(mc.level, mc.player, Reach.QUILL);
        return trace != null && trace.getType() == HitResult.Type.BLOCK ? trace : null;
    }

    /** Says {@code key} above the hotbar. */
    public static void status(String key, Object... args) {
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(key, args), true);
    }
}
