package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.Rgb;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class LightingText {
    public static final int TEXT = 0xC8D0DA;
    public static final int MUTED = 0x9AA7B8;
    public static final int ON = 0x68E38B;
    public static final int OFF = 0xF4777A;
    public static final int NUMBER = 0x73D9F2;
    public static final int MODE = 0xD6B4FF;
    public static final int WARNING = 0xF3C76E;

    private LightingText() {
    }

    public static int readable(int rgb) {
        rgb &= 0xFFFFFF;
        float brightness = Rgb.luma(rgb);
        return brightness >= 150 ? rgb : Rgb.mix(rgb, 0xFFFFFF, (150 - brightness) / (255 - brightness));
    }

    public static int groupColor(@Nullable LightingSettings settings, int group) {
        return settings == null || group < 0 || group >= LightingSettings.GROUPS ? TEXT : readable(settings.groups[group].colors[0]);
    }

    public static MutableComponent text(Object value, int color) {
        return (value instanceof Component component ? component.copy() : Component.literal(String.valueOf(value))).withColor(color);
    }

    public static MutableComponent group(@Nullable LightingSettings settings, int group) {
        return Component.translatable("create_media_systems.lighting.group", group + 1).withColor(groupColor(settings, group));
    }

    public static MutableComponent on(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off").withColor(enabled ? ON : OFF);
    }

    public static MutableComponent number(Object value) {
        return text(value, NUMBER);
    }

    public static MutableComponent source(boolean linked, boolean active) {
        return text(active ? "●" : "—", active ? ON : linked ? OFF : MUTED);
    }

    public static MutableComponent mode(LightingSettings.Mode mode) {
        return mode(mode.key());
    }

    /** The name under {@code create_media_systems.lighting.}{@code key}, in the colour of a mode. */
    public static MutableComponent mode(String key) {
        return Component.translatable("create_media_systems.lighting." + key).withColor(MODE);
    }

    /** Preset key {@code slot}'s name: a built-in preset's, or with {@code custom} the console's saved slot's. */
    public static MutableComponent presetName(LightingConsoleBlock.Entity console, int slot, boolean custom) {
        boolean empty = custom && console.presets[slot] == null;
        return Component.translatable("create_media_systems.lighting." + (custom
                        ? empty ? "preset_empty" : "preset_slot" : LightingSettings.Preset.values()[slot].key),
                text(slot + 1, empty ? MUTED : NUMBER))
                .withColor(empty ? MUTED : MODE);
    }

    public static MutableComponent label(String key, Component value) {
        return Component.translatable("create_media_systems.lighting.label." + key).withColor(TEXT)
                .append(": ").append(value.copy());
    }

    /** The message under {@code create_media_systems.lighting.}{@code key}, in plain text colour. */
    public static MutableComponent message(String key, Object... args) {
        return message(TEXT, key, args);
    }

    /** The message under {@code create_media_systems.lighting.}{@code key}, in {@code color} (ON, OFF, WARNING, MUTED). */
    public static MutableComponent message(int color, String key, Object... args) {
        return Component.translatable("create_media_systems.lighting." + key, args).withColor(color);
    }
}
