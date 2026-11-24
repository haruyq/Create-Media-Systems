package org.haruyq.createmediasystems.blocks.dj.ddjflx4.client;

import java.util.Locale;

import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.Control.Kind;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DdjFlx4Block;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.DjState;
import org.haruyq.createmediasystems.blocks.dj.ddjflx4.client.DjClient.View;
import org.haruyq.createmediasystems.client.InstrumentScreen;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A DDJ-FLX4's screen: rekordbox's decks as a Monitor shows them (see DjDisplay), and under them the controller seen
 * from above, to be worked with the mouse. A button is held while the left button is, and held with SHIFT while the
 * right one is; a knob turns as it is dragged up and down or the wheel is turned over it, and goes back to its middle
 * on a right click; a fader follows the mouse; a jog wheel turns with the mouse going round it. Its Host button makes
 * the controller this player's, or gives it up.
 */
final class DjScreen extends InstrumentScreen {
    /** How far (GUI pixels) a knob is dragged from one end to the other. */
    private static final float KNOB_DRAG = 110;
    /** How long (ticks) a control stays as this player left it, till the controller says how it stands: it says so
     * as late as its sound is heard, about half a second. */
    private static final int KEEP_TICKS = 24;

    private final BlockPos pos;
    private Button host;
    /** The display's corner and size of a unit, and the controller's: a unit of the top view in GUI pixels. */
    private float displayX, displayY, displayUnit, left, top, unit;
    /** The button, pad or jog wheel the mouse holds down (-1: none), and whether SHIFT is held with it. */
    private int held = -1;
    private boolean heldShift;
    /** The knob or fader the mouse moves (-1: none), where it has it, and whether that is yet to be sent. */
    private int moved = -1, movedTo, keep;
    private boolean unsent;
    /** Where on the jog wheel the mouse is (radians), and the steps turned that are yet to be sent. */
    private double jogAngle;
    private float jogSteps;

    DjScreen(BlockPos pos) {
        super(Component.translatable("block.create_media_systems.ddj_flx4"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        float across = Control.RIGHT - Control.LEFT, deep = Control.FRONT - Control.BACK;
        float wide = Math.min(width * .96f, (height - 38) / (DjDisplay.HEIGHT / DjDisplay.WIDTH + deep / across + .02f));
        displayUnit = wide / DjDisplay.WIDTH;
        displayX = left = (width - wide) / 2;
        displayY = 22;
        unit = wide / across;
        top = displayY + wide * DjDisplay.HEIGHT / DjDisplay.WIDTH + wide * .02f;
        if (addToggle(width - 8, 3)) return;
        host = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if (DjClient.hosting(pos)) {
                DjClient.stop();
            } else {
                DjClient.host(pos);
            }
        }).bounds(16 + font.width(title), 3, 70, 16)
                .tooltip(Tooltip.create(Component.translatable("create_media_systems.ddj_flx4.host_help"))).build());
    }

    @Nullable
    private DdjFlx4Block.Entity controller() {
        return minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof DdjFlx4Block.Entity controller
                ? controller : null;
    }

    @Override
    public void tick() {
        // Only the screen goes: its player keeps hosting from wherever they are.
        if (minecraft.player == null || DdjFlx4Block.near(minecraft.player, pos) == null) {
            onClose();
            return;
        }
        // A button let go of in another window never comes back here.
        if (held >= 0 && !minecraft.isWindowActive()) {
            mouseReleased(0, 0, 0);
        }
        if (unsent && moved >= 0) {
            unsent = false;
            DjClient.touch(pos, moved, movedTo, false);
        }
        if (Math.abs(jogSteps) >= 1 && held >= 0) {
            int steps = (int) jogSteps;
            jogSteps -= steps;
            DjClient.touch(pos, held, steps, true);
        }
        if (keep > 0 && --keep == 0) {
            moved = -1;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        DdjFlx4Block.Entity controller = controller();
        if (controller == null || hidden) {
            return;
        }
        boolean mine = DjClient.hosting(pos);
        host.setMessage(Component.translatable(mine ? "create_media_systems.ddj_flx4.stop" : "create_media_systems.ddj_flx4.host"));
        host.active = mine || controller.host() == null;
        drawHeader(g, 8, 7, Component.empty(), 0);
        g.drawString(font, status(controller), host.getRight() + 6, 7, 0xFFFFFFFF);
        DjClient.Status status = DjClient.status(pos);
        if (status != null && !status.problem().getString().isEmpty()) {
            // Along the bottom, as much of it as fits.
            g.drawCenteredString(font, font.plainSubstrByWidth(status.problem().getString(), width - 8), width / 2, height - 11, 0xFFFF5555);
        }

        View view = DjClient.view(controller);
        if (moved >= 0) {
            // As this player has it, till the controller says so itself.
            DjState shown = new DjState(view.state().bytes());
            shown.controls[moved] = (short) movedTo;
            view = new View(shown, view.seconds(), view.tracks(), view.level());
        }
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(displayX, displayY, 50);
        pose.scale(displayUnit, displayUnit, displayUnit);
        DjDisplay.draw(view, pose, g.bufferSource(), false, displayUnit * (float) minecraft.getWindow().getGuiScale());
        g.flush();
        pose.popPose();

        int hovered = slotAt(mouseX, mouseY);
        pose.pushPose();
        pose.translate(left, top, 150);
        // The model lies in its block's sixteenths, its front towards +z and its top up: seen from above here.
        float sixteenth = unit / DdjFlx4Block.SCALE;
        pose.scale(sixteenth, -sixteenth, sixteenth);
        pose.mulPose(Axis.XP.rotationDegrees(90));
        pose.translate(0, 0, -DdjFlx4Block.REAR);
        // Lit from where it is looked at, so that its top is as bright as it is printed.
        RenderSystem.setShaderLights(new Vector3f(0, 0, 1), new Vector3f(0, 0, -1));
        DjRenderer.draw(view, pose, g.bufferSource(), LightTexture.FULL_BRIGHT,
                sixteenth * 16 * (float) minecraft.getWindow().getGuiScale(), held >= 0 ? held : hovered);
        g.flush();
        Lighting.setupFor3DItems();
        pose.popPose();
        if (hovered >= 0 && held < 0 && moved < 0) {
            g.renderTooltip(font, Component.translatable("create_media_systems.ddj_flx4.control."
                    + Control.ofSlot(hovered).name().toLowerCase(Locale.ROOT)), mouseX, mouseY);
        }
    }

    private Component status(DdjFlx4Block.Entity controller) {
        DjClient.Status status = DjClient.status(pos);
        if (status == null) {
            return controller.host() == null ? Component.translatable("create_media_systems.ddj_flx4.free").withStyle(ChatFormatting.GRAY)
                    : Component.translatable("create_media_systems.drive.host", controller.hostName());
        }
        MutableComponent line = lamp("controller", status.hardware()).append("  ").append(lamp("rekordbox", status.rekordbox()))
                .append("  ").append(lamp("sound", status.sound()));
        if (!status.out().isEmpty()) {
            line.append("  ").append(Component.translatable("create_media_systems.ddj_flx4.midi_out", status.out()).withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    private static MutableComponent lamp(String what, boolean on) {
        return Component.translatable("create_media_systems.ddj_flx4." + what)
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
    }

    private float viewX(double mouseX) {
        return Control.LEFT + (float) (mouseX - left) / unit;
    }

    private float viewY(double mouseY) {
        return Control.BACK + (float) (mouseY - top) / unit;
    }

    private int slotAt(double mouseX, double mouseY) {
        return hidden ? -1 : Control.at(viewX(mouseX), viewY(mouseY));
    }

    private int value(int slot) {
        DdjFlx4Block.Entity controller = controller();
        return slot == moved ? movedTo : controller == null ? 0 : DjClient.view(controller).state().controls[slot];
    }

    private void move(int slot, int to) {
        if (unsent && moved >= 0 && moved != slot) {
            // Another control within the tick: the one before is told first.
            DjClient.touch(pos, moved, movedTo, false);
        }
        moved = slot;
        movedTo = Math.clamp(to, 0, Control.FULL);
        unsent = true;
        keep = 0;
    }

    /** A fader's value with the mouse there along it. */
    private int along(Control control, int deck, double mouseX, double mouseY) {
        float place = control.kind == Kind.CROSSFADER ? (viewX(mouseX) - (control.x(deck) - control.width / 2)) / control.width
                : (control.y + control.depth / 2 - viewY(mouseY)) / control.depth;
        place = Math.clamp(place, 0, 1);
        // As DjState#place has it.
        return Math.round((control == Control.TEMPO ? 1 - place : place) * Control.FULL);
    }

    private double angle(Control jog, int deck, double mouseX, double mouseY) {
        return Math.atan2(viewY(mouseY) - jog.y, viewX(mouseX) - jog.x(deck));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int slot = slotAt(mouseX, mouseY);
        if (slot < 0 || held >= 0) {
            return false;
        }
        Control control = Control.ofSlot(slot);
        int deck = slot & 1;
        switch (control.kind) {
            case BUTTON, PAD, ENCODER, JOG -> {
                heldShift = button == 1 && control != Control.SHIFT;
                if (heldShift) {
                    DjClient.touch(pos, Control.SHIFT.slot(deck), 1, false);
                }
                DjClient.touch(pos, slot, 1, false);
                held = slot;
                jogAngle = angle(control, deck, mouseX, mouseY);
                jogSteps = 0;
            }
            case KNOB -> move(slot, button == 1 ? control.rest() : value(slot));
            case FADER, CROSSFADER -> move(slot, along(control, deck, mouseX, mouseY));
            case SWITCH -> DjClient.touch(pos, slot, (value(slot) + 1) % 3, false);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (held >= 0 && Control.ofSlot(held).kind == Kind.JOG) {
            double now = angle(Control.JOG, held & 1, mouseX, mouseY);
            // On the screen an angle grows clockwise, as the wheel's steps do.
            jogSteps += (float) (Math.IEEEremainder(now - jogAngle, 2 * Math.PI) / (2 * Math.PI) * Control.JOG_STEPS);
            jogAngle = now;
            return true;
        }
        if (moved >= 0 && keep == 0) {
            Control control = Control.ofSlot(moved);
            move(moved, control.kind == Kind.KNOB ? movedTo - Math.round((float) dragY * Control.FULL / KNOB_DRAG)
                    : along(control, moved & 1, mouseX, mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (held >= 0) {
            DjClient.touch(pos, held, 0, false);
            if (heldShift) {
                DjClient.touch(pos, Control.SHIFT.slot(held & 1), 0, false);
            }
            held = -1;
        }
        if (moved >= 0 && keep == 0) {
            keep = KEEP_TICKS;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int slot = slotAt(mouseX, mouseY);
        if (slot < 0 || scrollY == 0) {
            return false;
        }
        Control control = Control.ofSlot(slot);
        if (control.kind == Kind.ENCODER) {
            DjClient.touch(pos, slot, scrollY > 0 ? -1 : 1, true);
        } else if (control.kind.continuous()) {
            move(slot, value(slot) + (int) Math.signum(scrollY) * Control.FULL / 64);
            keep = KEEP_TICKS;
        }
        return true;
    }

    @Override
    public void removed() {
        // Nothing stays held with nobody at it.
        mouseReleased(0, 0, 0);
    }
}
