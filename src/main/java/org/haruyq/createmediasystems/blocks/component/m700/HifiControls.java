package org.haruyq.createmediasystems.blocks.component.m700;

import org.haruyq.createmediasystems.client.ControlDrag;
import org.haruyq.createmediasystems.client.TextPromptScreen;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class HifiControls {
    /** How far across the stack (sixteenths) the crosshair goes to turn a knob all the way. */
    private static final double KNOB_TRAVEL = 4;
    private static final long PRESSED_MILLIS = 180;

    @Nullable
    private static BlockPos hoveredAt;
    @Nullable
    private static Control hovered;
    @Nullable
    private static BlockPos pressedAt;
    @Nullable
    private static Control pressed;
    private static long pressedTime;

    private HifiControls() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        ControlDrag.onUse(HifiControls::press);
    }

    @Nullable
    static Control hovered(HifiStackBlock.Entity stack) {
        return ControlDrag.held() instanceof Knob knob && knob.pos.equals(stack.getBlockPos()) ? knob.control
                : stack.getBlockPos().equals(hoveredAt) ? hovered : null;
    }

    @Nullable
    static Control pressed(HifiStackBlock.Entity stack) {
        return stack.getBlockPos().equals(pressedAt) && Util.getMillis() - pressedTime < PRESSED_MILLIS ? pressed : null;
    }

    @Nullable
    private static HifiStackBlock.Entity stack(@Nullable BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return pos != null && level != null && level.getBlockEntity(pos) instanceof HifiStackBlock.Entity stack ? stack : null;
    }

    /** Where {@code look} (in its block space; see ControlDrag#look) crosses the front or back of {@code stack}. */
    @Nullable
    private static HifiLayout.Aim aim(HifiStackBlock.Entity stack, ControlDrag.Look look) {
        return HifiLayout.aim(stack.getBlockState().getValue(HifiStackBlock.FACING),
                look.eye().subtract(Vec3.atLowerCornerOf(stack.getBlockPos())), look.along());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            hovered = null;
            return;
        }
        Control was = hovered;
        BlockPos wasAt = hoveredAt;
        hovered = null;
        hoveredAt = null;
        if (mc.screen == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && ControlDrag.works(mc.player)) {
            HifiStackBlock.Entity stack = stack(hit.getBlockPos());
            HifiLayout.Aim aim = stack == null ? null : aim(stack, ControlDrag.look(stack.getBlockPos()));
            if (aim != null) {
                hovered = HifiLayout.control(stack.units(), aim);
                hoveredAt = hovered == null ? null : hit.getBlockPos().immutable();
            }
        }
        if (ControlDrag.held() == null && hovered != null && (hovered != was || !hoveredAt.equals(wasAt))) {
            mc.gui.setOverlayMessage(hovered.label(), false);
        }
    }

    private static boolean press() {
        HifiStackBlock.Entity stack = stack(hoveredAt);
        if (hovered == null || stack == null) {
            return false;
        }
        Control control = hovered;
        BlockPos pos = hoveredAt;
        if (control.kind == Control.Kind.KNOB && stack.live(control.unit)) {
            HifiLayout.Aim aim = aim(stack, ControlDrag.look(pos));
            if (aim == null) {
                return false;
            }
            ControlDrag.hold(new Knob(pos, control, aim.x(), stack.knob(control)));
            stack.turning(control);
            return true;
        }
        pressed = control;
        pressedAt = pos;
        pressedTime = Util.getMillis();
        if (control.unit == Unit.TUNER && control.kind == Control.Kind.BUTTON && stack.live(Unit.TUNER)
                && (stack.tuner().station == control.index || stack.tuner().station(control.index).isEmpty())) {
            TextPromptScreen.open("create_media_systems.screen.station", stack.tuner().station(control.index),
                    UrlPolicy.MAX_LENGTH, url -> PacketDistributor.sendToServer(
                            new HifiStackBlock.SetStation(pos, control.index, url)));
            return true;
        }
        PacketDistributor.sendToServer(new HifiStackBlock.Press(pos, control.ordinal(), stack.knob(control)));
        return true;
    }

    private static final class Knob implements ControlDrag.Held {
        private final BlockPos pos;
        private final Control control;
        private final double grabbedAt;
        private final int grabbedValue;
        private int sent;

        Knob(BlockPos pos, Control control, double grabbedAt, int grabbedValue) {
            this.pos = pos.immutable();
            this.control = control;
            this.grabbedAt = grabbedAt;
            this.grabbedValue = sent = grabbedValue;
        }

        @Override
        public BlockPos pos() {
            return pos;
        }

        @Override
        public void drag(ControlDrag.Look look) {
            HifiStackBlock.Entity stack = stack(pos);
            if (stack == null) {
                return;
            }
            HifiLayout.Aim aim = aim(stack, look);
            if (aim != null && !aim.rear()) {
                stack.turn(control, Mth.clamp(grabbedValue + (int) Math.round((aim.x() - grabbedAt) * 100 / KNOB_TRAVEL), 0, 100));
            }
            send(stack, Minecraft.getInstance().level.getGameTime() % 2 == 0);
        }

        @Override
        public void release(boolean gone) {
            HifiStackBlock.Entity stack = stack(pos);
            if (stack != null) {
                send(stack, true);
                stack.turning(null);
            }
        }

        private void send(HifiStackBlock.Entity stack, boolean now) {
            int value = stack.knob(control);
            if (value != sent && now) {
                sent = value;
                PacketDistributor.sendToServer(new HifiStackBlock.Press(pos, control.ordinal(), value));
            }
            Minecraft.getInstance().gui.setOverlayMessage(Component.empty().append(control.label()).append("  " + value + "%"), false);
        }
    }
}
