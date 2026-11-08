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
    /** Saved settings a console keeps for its groups to recall. */
    public static final int PRESETS = 6;
    /** How far a held Shift moves a range at once, against 1 without. */
    public static final int FAST_RANGE_STEP = 10;
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
            group.range += amount;
            group.normalize();
        }
    }

    /** Brings every setting within what it can be, as a saved or sent one may not be. */
    public void normalize() {
        master = bounded(master, 0, 1, 0.8f);
        selectedGroup = Mth.clamp(selectedGroup, 0, GROUPS - 1);
        selectedColor = Mth.clamp(selectedColor, 0, COLORS - 1);
        for (Group group : groups) {
            group.normalize();
        }
    }

    public static final class Group {
        public static final int MOTION_FIXED = 0, MOTION_SWEEP = 1, MOTION_CIRCLE = 2, MOTION_TRACK = 3, MOTIONS = 4;
        public static final int PATTERN_SPOT = 0, PATTERN_BEAM = 1, PATTERN_LASER_RAY = 2, PATTERN_LASER_FAN = 3,
                PATTERN_LASER_SHEET = 4, PATTERNS = 5;
        public static final int MAPPING_SEQUENCE = 0, MAPPING_SPLIT = 1, MAPPING_GRADIENT = 2, MAPPING_PER_FIXTURE = 3,
                MAPPINGS = 4;
        public static final int BAND_ALL = 0, BAND_BASS = 1, BAND_MID = 2, BAND_HIGH = 3, BANDS = 4;
        /** colorMode: the group's own colours, or its video source's. */
        public static final int COLORS_OWN = 0, COLORS_VIDEO = 1;

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
            motion = slot == 0 ? MOTION_FIXED : slot == 3 ? MOTION_CIRCLE : slot == 5 ? MOTION_TRACK : MOTION_SWEEP;
            pattern = slot == 0 || slot == 5 ? PATTERN_SPOT : slot < 3 ? PATTERN_BEAM
                    : slot == 3 ? PATTERN_LASER_FAN : PATTERN_LASER_SHEET;
            audio = slot > 0 && slot < 5;
            colorCycle = slot == 2 || slot == 4;
            colorMode = COLORS_OWN;
            audioBand = BAND_ALL;
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
            System.arraycopy(saved, 0, colors, 0, Math.min(COLORS, saved.length));
            colorCount = tag.getInt("ColorCount");
            mapping = tag.getInt("Mapping");
            colorMode = tag.getInt("ColorMode");
            motion = tag.getInt("Motion");
            pattern = tag.getInt("Pattern");
            audioBand = tag.getInt("AudioBand");
            audio = tag.getBoolean("Audio");
            colorCycle = tag.getBoolean("ColorCycle");
            brightness = tag.getFloat("Brightness");
            range = tag.getFloat("Range");
            spread = tag.getFloat("Spread");
            speed = tag.getFloat("Speed");
            pan = tag.getFloat("Pan");
            tilt = tag.getFloat("Tilt");
            target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
            normalize();
        }

        /** Brings every setting within what it can be (see LightingSettings#normalize). */
        public void normalize() {
            for (int i = 0; i < COLORS; i++) {
                colors[i] &= 0xFFFFFF;
            }
            colorCount = Mth.clamp(colorCount, 1, COLORS);
            mapping = Mth.clamp(mapping, 0, MAPPINGS - 1);
            colorMode = Mth.clamp(colorMode, COLORS_OWN, COLORS_VIDEO);
            motion = Mth.clamp(motion, 0, MOTIONS - 1);
            pattern = Mth.clamp(pattern, 0, PATTERNS - 1);
            audioBand = Mth.clamp(audioBand, 0, BANDS - 1);
            brightness = bounded(brightness, 0, 1, 1);
            range = bounded(range, 2, MAX_RANGE, 32);
            spread = bounded(spread, 1, 70, 12);
            speed = bounded(speed, 0.05f, 4, 1);
            pan = bounded(pan, -180, 180, 0);
            tilt = bounded(tilt, -85, 85, 35);
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
        master = tag.getFloat("Master");
        selectedGroup = tag.getInt("SelectedGroup");
        selectedColor = tag.getInt("SelectedColor");
        ListTag list = tag.getList("Groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(GROUPS, list.size()); i++) {
            groups[i].load(list.getCompound(i));
        }
        normalize();
    }

    private static float bounded(float value, float min, float max, float fallback) {
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
        boolean videoColors = group.colorMode == Group.COLORS_VIDEO && video;
        int n = videoColors ? 2 : group.colorCount;
        double position = switch (group.mapping) {
            case Group.MAPPING_SPLIT -> Math.min(n - 1, Math.max(0, order) * n / Math.max(1, count));
            case Group.MAPPING_GRADIENT -> count <= 1 ? 0 : Mth.clamp(order / (double) (count - 1), 0, 1) * (n - 1);
            default -> Math.floorMod(order, n);
        };
        if (group.colorCycle) {
            position += seconds * group.speed / 2;
        }
        int index = Math.floorMod((int) Math.floor(position), n);
        int next = (index + 1) % n;
        int a = videoColors ? (index == 0 ? leftColor : rightColor) : group.colors[index];
        int b = videoColors ? (next == 0 ? leftColor : rightColor) : group.colors[next];
        return mix(a, b, (float) (position - Math.floor(position)));
    }
}
