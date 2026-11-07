package org.haruyq.createmediasystems.lighting;

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
        float brightness = ((rgb >> 16 & 255) * 54 + (rgb >> 8 & 255) * 183 + (rgb & 255) * 19) / 256f;
        return brightness >= 150 ? rgb : LightingSettings.mix(rgb, 0xFFFFFF, (150 - brightness) / (255 - brightness));
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

    public static MutableComponent mode(String key, int value) {
        return Component.translatable("create_media_systems.lighting." + key + "." + value).withColor(MODE);
    }

    public static MutableComponent label(String key, Component value) {
        return Component.translatable("create_media_systems.lighting.label." + key).withColor(TEXT)
                .append(": ").append(value.copy());
    }

    public static MutableComponent message(String key, Object... args) {
        int color = switch (key) {
            case "preset_saved", "preset_loaded", "picked", "linked", "source_linked", "following" -> ON;
            case "unlinked" -> OFF;
            case "preset_choose_slot", "preset_save_help", "preset_start_help", "pick_first", "too_far", "full" -> WARNING;
            case "preset_cancelled", "preset_empty_help", "link_help", "track_help", "controls" -> MUTED;
            default -> TEXT;
        };
        return Component.translatable("create_media_systems.lighting." + key, args).withColor(color);
    }
}
