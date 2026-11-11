package org.haruyq.createmediasystems.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** The flat control panel of a portable device's screen: its buttons, its battery gauge and its time readout. */
public final class DevicePanel {
    /** A panel's colours: button face idle/hovered, its edge stripe idle/hovered, and its label. */
    public record Palette(int face, int faceHover, int edge, int edgeHover, int label) {}

    private DevicePanel() {}

    /** A 20-pixel-high flat button with a coloured edge stripe. */
    public static Button button(int x, int y, int width, Component text, Palette palette, Runnable press) {
        return new Control(x, y, width, text, palette, press);
    }

    private static final class Control extends Button {
        private final Palette palette;

        Control(int x, int y, int width, Component text, Palette palette, Runnable press) {
            super(x, y, width, 20, text, b -> press.run(), DEFAULT_NARRATION);
            this.palette = palette;
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            boolean hover = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + width, getY() + height, hover ? palette.faceHover : palette.face);
            g.fill(getX(), getY(), getX() + 2, getY() + height, hover ? palette.edgeHover : palette.edge);
            g.drawCenteredString(Minecraft.getInstance().font, getMessage(), getX() + width / 2, getY() + 6, palette.label);
        }
    }

    /** A gauge from (x0, y0) to (x1, y1), {@code fraction} (0..1) of it filled from the left. */
    public static void gauge(GuiGraphics g, int x0, int y0, int x1, int y1, float fraction, int track, int fill) {
        g.fill(x0, y0, x1, y1, track);
        g.fill(x0, y0, x0 + Math.round((x1 - x0) * Math.clamp(fraction, 0, 1)), y1, fill);
    }

    /** {@code fraction} (0..1) as a whole percentage, "57%". */
    public static String percent(float fraction) {
        return Math.round(100 * fraction) + "%";
    }

    /** A play position of {@code ticks} as minutes and seconds, "03:07". */
    public static String clock(long ticks) {
        long seconds = ticks / 20;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
