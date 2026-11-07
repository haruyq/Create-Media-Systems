package org.haruyq.createmediasystems.lighting;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

public final class LightingSettings {
    public static final int GROUPS = 6;
    public static final int COLORS = 8;
    public static final int MAX_RANGE = 300;
    public final Group[] groups = new Group[GROUPS];
    public int selectedGroup;
    public int selectedColor;
    public boolean enabled = true;
    public float master = 0.8f;

    public LightingSettings() {
        for (int i = 0; i < GROUPS; i++) {
            groups[i] = new Group();
        }
    }

    public void shiftRange(int amount) {
        for (Group group : groups) {
            group.range = Mth.clamp(group.range + amount, 2, MAX_RANGE);
        }
    }

    public static final class Group {
        public final int[] colors = {0x20C0FF, 0xA040FF, 0xFF3080, 0xFFD060, 0xFFFFFF, 0x40FF80, 0x2040FF, 0xFF3030};
        public int colorCount = 2;
        public int mapping;
        public int colorMode;
        public int motion;
        public int pattern;
        public int audioBand;
        public boolean audio;
        public boolean colorCycle;
        public float brightness = 1;
        public float range = 32;
        public float spread = 12;
        public float speed = 1;
        public float pan;
        public float tilt = 35;
        public UUID target;

        public void preset(int slot, UUID player) {
            motion = slot == 0 ? 0 : slot == 3 ? 2 : slot == 5 ? 3 : 1;
            pattern = slot == 0 || slot == 5 ? 0 : slot < 3 ? 1 : slot == 3 ? 3 : 4;
            audio = slot > 0 && slot < 5;
            colorCycle = slot == 2 || slot == 4;
            colorMode = 0;
            audioBand = 0;
            speed = 1;
            spread = slot == 5 ? 30 : slot >= 3 ? 40 : 12;
            if (slot == 5 && player != null) {
                target = player;
            }
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray("Colors", colors.clone());
            tag.putInt("ColorCount", colorCount);
            tag.putInt("Mapping", mapping);
            tag.putInt("ColorMode", colorMode);
            tag.putInt("Motion", motion);
            tag.putInt("Pattern", pattern);
            tag.putInt("AudioBand", audioBand);
            tag.putBoolean("Audio", audio);
            tag.putBoolean("ColorCycle", colorCycle);
            tag.putFloat("Brightness", brightness);
            tag.putFloat("Range", range);
            tag.putFloat("Spread", spread);
            tag.putFloat("Speed", speed);
            tag.putFloat("Pan", pan);
            tag.putFloat("Tilt", tilt);
            if (target != null) {
                tag.putUUID("Target", target);
            }
            return tag;
        }

        public void load(CompoundTag tag) {
            int[] saved = tag.getIntArray("Colors");
            for (int i = 0; i < Math.min(COLORS, saved.length); i++) {
                colors[i] = saved[i] & 0xFFFFFF;
            }
            colorCount = Mth.clamp(tag.getInt("ColorCount"), 1, COLORS);
            mapping = Mth.clamp(tag.getInt("Mapping"), 0, 3);
            colorMode = Mth.clamp(tag.getInt("ColorMode"), 0, 1);
            motion = Mth.clamp(tag.getInt("Motion"), 0, 3);
            pattern = Mth.clamp(tag.getInt("Pattern"), 0, 4);
            audioBand = Mth.clamp(tag.getInt("AudioBand"), 0, 3);
            audio = tag.getBoolean("Audio");
            colorCycle = tag.getBoolean("ColorCycle");
            brightness = bounded(tag.getFloat("Brightness"), 0, 1, 1);
            range = bounded(tag.getFloat("Range"), 2, MAX_RANGE, 32);
            spread = bounded(tag.getFloat("Spread"), 1, 70, 12);
            speed = bounded(tag.getFloat("Speed"), 0.05f, 4, 1);
            pan = bounded(tag.getFloat("Pan"), -180, 180, 0);
            tilt = bounded(tag.getFloat("Tilt"), -85, 85, 35);
            target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled);
        tag.putFloat("Master", master);
        tag.putInt("SelectedGroup", selectedGroup);
        tag.putInt("SelectedColor", selectedColor);
        ListTag list = new ListTag();
        for (Group group : groups) {
            list.add(group.save());
        }
        tag.put("Groups", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        enabled = tag.getBoolean("Enabled");
        master = bounded(tag.getFloat("Master"), 0, 1, 0.8f);
        selectedGroup = Mth.clamp(tag.getInt("SelectedGroup"), 0, GROUPS - 1);
        selectedColor = Mth.clamp(tag.getInt("SelectedColor"), 0, COLORS - 1);
        ListTag list = tag.getList("Groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(GROUPS, list.size()); i++) {
            groups[i].load(list.getCompound(i));
        }
    }

    public static float bounded(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
    }

    public static int palette(int column, int row) {
        float x = Mth.clamp(column, 0, 7) / 7f;
        float y = Mth.clamp(row, 0, 7) / 7f;
        int top = Math.round(Math.min(1, (1 - x) * 2) * 255) << 8 | Math.round(Math.min(1, x * 2) * 255);
        int bottom = 0xFF0000 | Math.round((1 - x) * 140) << 8 | Math.round(x * 255);
        return mix(top, bottom, y);
    }

    public static int mix(int a, int b, float amount) {
        float t = Mth.clamp(amount, 0, 1);
        int r = Math.round((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = Math.round((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int blue = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | blue;
    }

    public static int color(Group group, int order, int count, double seconds, int leftColor, int rightColor,
                            boolean video) {
        int n = group.colorMode == 1 && video ? 2 : group.colorCount;
        double position = switch (group.mapping) {
            case 1 -> Math.min(n - 1, Math.max(0, order) * n / Math.max(1, count));
            case 2 -> count <= 1 ? 0 : Mth.clamp(order / (double) (count - 1), 0, 1) * (n - 1);
            default -> Math.floorMod(order, n);
        };
        if (group.colorCycle) {
            position += seconds * group.speed / 2;
        }
        int index = Math.floorMod((int) Math.floor(position), n);
        int next = (index + 1) % n;
        int a = group.colorMode == 1 && video ? (index == 0 ? leftColor : rightColor) : group.colors[index];
        int b = group.colorMode == 1 && video ? (next == 0 ? leftColor : rightColor) : group.colors[next];
        return mix(a, b, (float) (position - Math.floor(position)));
    }
}
