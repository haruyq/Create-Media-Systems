package org.haruyq.createmediasystems.hifi;

import org.haruyq.createmediasystems.audio.AudioFeature;
import org.haruyq.createmediasystems.display.DisplayFeature;
import org.haruyq.createmediasystems.lighting.LightingFeature;
import org.haruyq.createmediasystems.video.VideoFeature;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.TextPromptScreen;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.wrench.WrenchItem;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Working a hi-fi stack by hand: point at a control on its front or a socket on its back and right-click. What is
 * pointed at is outlined and named above the hotbar. A knob is held and turned by looking left or right along the
 * stack: it is heard at once here, and set for everyone as it turns. Sneaking, or holding a block, a quill or a
 * wrench, leaves the stack to those.
 */
public final class HifiControls {
    /** How far across the stack (sixteenths) the crosshair goes to turn a knob all the way. */
    private static final double KNOB_TRAVEL = 4;
    /** How long (ms) a pressed button stays in. */
    private static final long PRESSED_MILLIS = 180;

    @Nullable
    private static BlockPos hoveredAt;
    @Nullable
    private static Control hovered;
    @Nullable
    private static BlockPos heldAt;
    @Nullable
    private static Control held;
    private static double grabbedAt;
    private static int grabbedValue;
    private static int sent;
    @Nullable
    private static BlockPos pressedAt;
    @Nullable
    private static Control pressed;
    private static long pressedTime;

    private HifiControls() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered event) -> {
            if (event.isUseItem() && (held != null || press())) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        });
    }

    /** The control of {@code stack} pointed at (or held); null for none. */
    @Nullable
    static Control hovered(HifiStackBlock.Entity stack) {
        return stack.getBlockPos().equals(heldAt) ? held : stack.getBlockPos().equals(hoveredAt) ? hovered : null;
    }

    /** The button of {@code stack} just pressed; null for none. */
    @Nullable
    static Control pressed(HifiStackBlock.Entity stack) {
        return stack.getBlockPos().equals(pressedAt) && Util.getMillis() - pressedTime < PRESSED_MILLIS ? pressed : null;
    }

    @Nullable
    private static HifiStackBlock.Entity stack(@Nullable BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return pos != null && level != null && level.getBlockEntity(pos) instanceof HifiStackBlock.Entity stack ? stack : null;
    }

    /** Whether {@code player}'s right-click is the stack's: not if they sneak, or hold something that works blocks itself. */
    private static boolean works(Player player) {
        ItemStack hand = player.getMainHandItem();
        return !player.isShiftKeyDown() && !(hand.getItem() instanceof BlockItem) && !(hand.getItem() instanceof WrenchItem)
                && !hand.is(AudioFeature.RANGE_QUILL.get()) && !hand.is(DisplayFeature.DISPLAY_QUILL.get())
                && !hand.is(LightingFeature.LIGHTING_LINK.get()) && !hand.is(VideoFeature.REMOTE.get());
    }

    /** Where the player's look crosses the front or back of {@code stack}, worked out in its block space (see SableCompat). */
    @Nullable
    private static HifiLayout.Aim aim(HifiStackBlock.Entity stack) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = stack.getBlockPos();
        Vec3 corner = Vec3.atLowerCornerOf(pos);
        Vec3 eye = SableCompat.toBlockSpace(mc.level, pos, mc.player.getEyePosition());
        Vec3 ahead = SableCompat.toBlockSpace(mc.level, pos, mc.player.getEyePosition().add(mc.player.getViewVector(1)));
        return HifiLayout.aim(stack.getBlockState().getValue(HifiStackBlock.FACING), eye.subtract(corner), ahead.subtract(eye));
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            hovered = held = null;
            HifiStackBlock.Entity.heldStack = null;
            return;
        }
        Control was = hovered;
        BlockPos wasAt = hoveredAt;
        hovered = null;
        hoveredAt = null;
        if (mc.screen == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && works(mc.player)) {
            HifiStackBlock.Entity stack = stack(hit.getBlockPos());
            HifiLayout.Aim aim = stack == null ? null : aim(stack);
            if (aim != null) {
                hovered = HifiLayout.control(stack.units(), aim);
                hoveredAt = hovered == null ? null : hit.getBlockPos().immutable();
            }
        }
        if (held == null && hovered != null && (hovered != was || !hoveredAt.equals(wasAt))) {
            mc.gui.setOverlayMessage(hovered.label(), false);
        }
        drag();
    }

    /** A right-click: works what is pointed at, if anything. */
    private static boolean press() {
        HifiStackBlock.Entity stack = stack(hoveredAt);
        if (hovered == null || stack == null) {
            return false;
        }
        Control control = hovered;
        BlockPos pos = hoveredAt;
        if (control.kind == Control.Kind.KNOB && stack.live(control.unit)) {
            HifiLayout.Aim aim = aim(stack);
            if (aim == null) {
                return false;
            }
            held = control;
            heldAt = pos;
            grabbedAt = aim.x();
            grabbedValue = sent = stack.knob(control);
            HifiStackBlock.Entity.heldStack = stack;
            HifiStackBlock.Entity.heldKnob = control;
            return true;
        }
        pressed = control;
        pressedAt = pos;
        pressedTime = Util.getMillis();
        // A station with no link yet, or the one tuned in pressed again, asks for its link.
        if (control.unit == Unit.TUNER && control.kind == Control.Kind.BUTTON && stack.live(Unit.TUNER)
                && (stack.station() == control.index || stack.station(control.index).isEmpty())) {
            TextPromptScreen.open("create_media_systems.screen.station", stack.station(control.index),
                    UrlPolicy.MAX_LENGTH, url -> PacketDistributor.sendToServer(
                            new HifiStackBlock.SetStation(pos, control.index, url)));
            return true;
        }
        PacketDistributor.sendToServer(new HifiStackBlock.Press(pos, control.ordinal(), stack.knob(control)));
        return true;
    }

    /** Turns the held knob with the crosshair; lets it go with right-click. */
    private static void drag() {
        if (held == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        HifiStackBlock.Entity stack = stack(heldAt);
        boolean letGo = stack == null || !mc.options.keyUse.isDown();
        if (stack != null) {
            HifiLayout.Aim aim = letGo ? null : aim(stack);
            if (aim != null && !aim.rear()) {
                stack.turn(held, Mth.clamp(grabbedValue + (int) Math.round((aim.x() - grabbedAt) * 100 / KNOB_TRAVEL), 0, 100));
            }
            int value = stack.knob(held);
            if (value != sent && (letGo || mc.level.getGameTime() % 2 == 0)) {
                sent = value;
                PacketDistributor.sendToServer(new HifiStackBlock.Press(heldAt, held.ordinal(), value));
            }
            mc.gui.setOverlayMessage(Component.empty().append(held.label()).append("  " + value + "%"), false);
        }
        if (letGo) {
            held = null;
            heldAt = null;
            HifiStackBlock.Entity.heldStack = null;
            HifiStackBlock.Entity.heldKnob = null;
        }
    }
}
