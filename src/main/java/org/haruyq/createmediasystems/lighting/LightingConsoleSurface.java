package org.haruyq.createmediasystems.lighting;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

/**
 * Draws on a lighting console as its own controls are drawn, for a LightingConsolePage. Deck coordinates are
 * (x across, 0 to 4; z from the front edge back, 0 to 1.55) and the deck rises to the back, so what is drawn at a
 * z sits on it there; monitor coordinates are (x across; y up, 1.12 to 1.86).
 */
public final class LightingConsoleSurface {
    private final MultiBufferSource buffers;
    private final Matrix4f matrix;
    private final int light;
    private final VertexConsumer metal;
    private final VertexConsumer ink;

    LightingConsoleSurface(MultiBufferSource buffers, Matrix4f matrix, int light) {
        this.buffers = buffers;
        this.matrix = matrix;
        this.light = light;
        metal = new LightingRenderer.Output(buffers, LightingRenderer.CONSOLE_FRAME);
        ink = new LightingRenderer.Output(buffers, LightingRenderer.SCREEN);
    }

    /**
     * For drawing what the rest of this class doesn't: the console's space into the frame's, where x runs across the
     * console from its left edge (0) to its right (4), y up from its foot (the deck at height(z), the monitors from
     * 1.12 to 1.86), and z back from the deck's front edge (0; the monitors lean back, at z = 1.455 + (y - 1) *
     * 0.4142). A copy, to transform freely.
     */
    public Matrix4f pose() {
        return new Matrix4f(matrix);
    }

    /** The frame's buffers, for drawing with any render type. */
    public MultiBufferSource buffers() {
        return buffers;
    }

    /** The packed light at the console, for lit render types. */
    public int light() {
        return light;
    }

    /** The deck's height at {@code z}. */
    public float height(float z) {
        return LightingRenderer.Console.height(z);
    }

    /** A flat coloured rectangle on the deck. */
    public void panel(float x0, float z0, float x1, float z1, int color) {
        LightingRenderer.Console.panel(ink, matrix, x0, z0, x1, z1, color);
    }

    /** A flat coloured rectangle on the deck, {@code depth} above (negative: below) its usual height. */
    public void panel(float x0, float z0, float x1, float z1, int color, float depth) {
        LightingRenderer.Console.panel(ink, matrix, x0, z0, x1, z1, color, depth);
    }

    public void panelBorder(float x0, float z0, float x1, float z1, float width, int color) {
        LightingRenderer.Console.panelBorder(ink, matrix, x0, z0, x1, z1, width, color);
    }

    /** A lit key, as a preset key: a metal cap with {@code color} on top, sunk while {@code pressed}. */
    public void key(float x0, float z0, float x1, float z1, int color, boolean pressed) {
        float depth = pressed ? -0.018f : 0;
        float z = (z0 + z1) / 2;
        LightingRenderer.box(metal, matrix, x0, height(z), z0, x1, height(z) + 0.05f + depth, z1, 0xAFB2C1, light);
        panel(x0 + 0.005f, z0 + 0.005f, x1 - 0.005f, z1 - 0.005f, color, depth);
    }

    /** A fader at {@code x}, as the group faders: {@code value} 0 at the front (z 0.25) to 1 at the back (z 1.2). */
    public void fader(float x, float value, int color) {
        LightingRenderer.Console.panel(ink, matrix, x - 0.023f, 0.23f, x + 0.023f, 1.23f, 0x0B0D14);
        float z = 0.25f + value * 0.95f;
        LightingRenderer.box(metal, matrix, x - 0.055f, height(z) + 0.015f, z - 0.065f,
                x + 0.055f, height(z) + 0.11f, z + 0.065f, color, light);
        LightingRenderer.Console.faderCap(ink, matrix, x, z, color);
    }

    /** The z a fader's {@code value} puts its cap at, and the value at a z (as a drag reads it). */
    public static float faderZ(float value) {
        return 0.25f + value * 0.95f;
    }

    public static float faderValue(double z) {
        return (float) ((z - 0.25) / 0.95);
    }

    /** A knob at ({@code x}, {@code z}), its pointer at {@code value} (0 to 1) of its sweep, in {@code accent}. */
    public void knob(float x, float z, int accent, float value) {
        LightingRenderer.Console.knob(metal, ink, matrix, x, z, accent, light, value);
    }

    /** Text lying on the deck, {@code width} at most across, its top left at ({@code x}, {@code z}). */
    public void deckText(float x, float z, String text, int color, float width) {
        LightingRenderer.Console.topText(buffers, matrix, x, z, text, color, width, 0);
    }

    /** A coloured rectangle on the monitors. */
    public void monitor(float x0, float x1, float y0, float y1, int color) {
        LightingRenderer.Console.monitor(ink, matrix, 0, x0, x1, y0, y1, color);
    }

    public void monitorBorder(float x0, float x1, float y0, float y1, int color) {
        LightingRenderer.Console.monitorBorder(ink, matrix, 0, x0, x1, y0, y1, color);
    }

    /** Text on the monitors, {@code width} at most across, its top left at ({@code x}, {@code y}). */
    public void monitorText(float x, float y, Component text, float width) {
        LightingRenderer.Console.text(buffers, matrix, x, y, text, width);
    }
}
