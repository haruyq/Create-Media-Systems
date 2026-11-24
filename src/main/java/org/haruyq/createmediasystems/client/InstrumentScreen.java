package org.haruyq.createmediasystems.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * What every instrument's screen has: the world in sight behind it, undimmed, and the game going on; a top line with
 * the instrument's name; and the button at its right end that puts the screen out of sight but for itself, while the
 * instrument still plays from the computer's keys and a MIDI keyboard, and dragging with the right button turns the
 * player to look around. It opens shown.
 */
public abstract class InstrumentScreen extends Screen {
    protected static final int NAME = 0xFF85858D;
    /** Whether it is out of sight, all but the button that brings it back. */
    protected boolean hidden;

    protected InstrumentScreen(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The world stays in sight behind it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** While hidden, a drag with the right button turns the player as the mouse does in game; nothing else takes it. */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (hidden && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && minecraft.player != null) {
            double sensitivity = minecraft.options.sensitivity().get() * .6 + .2;
            double scale = minecraft.getWindow().getGuiScale() * 8 * sensitivity * sensitivity * sensitivity;
            minecraft.player.turn(dragX * scale, dragY * scale * (minecraft.options.invertYMouse().get() ? -1 : 1));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    protected Component toggleName() {
        return Component.translatable(hidden ? "create_media_systems.keys.show" : "create_media_systems.keys.hide");
    }

    protected static Component toggleHint() {
        return Component.translatable("create_media_systems.keys.hide_help");
    }

    protected void toggleHidden() {
        hidden = !hidden;
        rebuildWidgets();
    }

    /**
     * For a screen in the GUI's pixels: the button that hides it, its right end at {@code right} and its top at
     * {@code top}; while hidden, in the window's bottom right corner. True while hidden, when nothing else is added.
     */
    protected boolean addToggle(int right, int top) {
        int wide = font.width(toggleName()) + 12, high = 16;
        addRenderableWidget(Button.builder(toggleName(), button -> toggleHidden()).tooltip(Tooltip.create(toggleHint()))
                .bounds(hidden ? width - 8 - wide : right - wide, hidden ? height - 8 - high : top, wide, high).build());
        return hidden;
    }

    /** For a screen in the GUI's pixels: the top line from ({@code x}, {@code y}), the instrument's name and then {@code status}. */
    protected void drawHeader(GuiGraphics g, int x, int y, Component status, int statusColor) {
        g.drawString(font, title, x, y, NAME, false);
        g.drawString(font, status, x + font.width(title) + 8, y, statusColor, false);
    }
}
