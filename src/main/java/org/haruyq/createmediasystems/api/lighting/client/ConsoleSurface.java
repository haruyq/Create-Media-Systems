package org.haruyq.createmediasystems.api.lighting.client;

import org.haruyq.createmediasystems.lighting.ConsoleLayout;
import org.haruyq.createmediasystems.lighting.LightingText;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Draws on a lighting console as its own controls are drawn, for a {@link ConsolePage}. Colours are 0xRRGGBB. */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface ConsoleSurface {
    /**
     * The console's space into the frame's, for drawing what the rest doesn't: x across from the console's left edge
     * (0) to its right (4), y up from its foot, z back from the deck's front edge. A copy.
     */
    Matrix4f pose();

    MultiBufferSource buffers();

    /** The packed light at the console. */
    int light();

    /** The deck's height at {@code z}. */
    float height(float z);

    /** A flat rectangle on the deck. */
    void panel(float x0, float z0, float x1, float z1, int color);

    /** A flat rectangle on the deck, {@code depth} above (negative: below) its usual height. */
    void panel(float x0, float z0, float x1, float z1, int color, float depth);

    void panelBorder(float x0, float z0, float x1, float z1, float width, int color);

    /** A lit key: a metal cap with {@code color} on top, sunk while {@code pressed}. */
    void key(float x0, float z0, float x1, float z1, int color, boolean pressed);

    /** A fader at {@code x}: {@code value} 0 at the front to 1 at the back (see faderZ). */
    void fader(float x, float value, int color);

    /** A knob at ({@code x}, {@code z}), its pointer at {@code value} (0 to 1) of its sweep. */
    void knob(float x, float z, int accent, float value);

    /** Text lying on the deck, {@code width} at most across, its top left at ({@code x}, {@code z}). */
    void deckText(float x, float z, String text, int color, float width);

    /** A rectangle on the monitors. */
    void monitor(float x0, float x1, float y0, float y1, int color);

    void monitorBorder(float x0, float x1, float y0, float y1, int color);

    /** Text on the monitors, {@code width} at most across, its top left at ({@code x}, {@code y}). */
    void monitorText(float x, float y, Component text, float width);

    /** The z a fader's {@code value} puts its cap at. */
    static float faderZ(float value) {
        return ConsoleLayout.faderZ(value);
    }

    /** The fader value at deck {@code z}, as a drag reads it. */
    static float faderValue(double z) {
        return ConsoleLayout.faderValue(z);
    }

    /** {@code rgb}, lightened if need be to read on the console's dark screens. */
    static int readable(int rgb) {
        return LightingText.readable(rgb);
    }

    /** {@code value} as text in {@code color}. */
    static MutableComponent text(Object value, int color) {
        return LightingText.text(value, color);
    }

    /** On or off, as the console says it. */
    static MutableComponent on(boolean enabled) {
        return LightingText.on(enabled);
    }

    static MutableComponent number(Object value) {
        return LightingText.number(value);
    }

    /** The name under {@code create_media_systems.lighting.}{@code key}, in the colour of a mode. */
    static MutableComponent mode(String key) {
        return LightingText.mode(key);
    }

    /** The label under {@code create_media_systems.lighting.label.}{@code key}, then ": " and {@code value}. */
    static MutableComponent label(String key, Component value) {
        return LightingText.label(key, value);
    }
}
