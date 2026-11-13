package org.haruyq.createmediasystems.lighting;

import org.jetbrains.annotations.Nullable;
import org.haruyq.createmediasystems.api.lighting.GroupView;
import java.util.UUID;

import org.haruyq.createmediasystems.Rgb;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

public final class LightingSettings {
    public static final int GROUPS = 6;
    public static final int COLORS = 8;
    public static final int MAX_RANGE = 300;
    /** How far from a fixture its light is drawn: its longest beam, and a margin. */
    public static final int VIEW_DISTANCE = MAX_RANGE + 32;
    /** Saved settings a console keeps for its groups to recall. */
    public static final int PRESETS = 6;
    /** How far a held Shift moves a range at once, against 1 without. */
    public static final int FAST_RANGE_STEP = 10;
    /** A new group's colours, and a video's two colours before it is first seen. */
    public static final int[] DEFAULT_COLORS = {0x20C0FF, 0xA040FF, 0xFF3080, 0xFFD060, 0xFFFFFF, 0x40FF80, 0x2040FF, 0xFF3030};

    /**
     * A number a console keeps: its save key, its value in a new console, what it may be, and how far one step (a
     * scroll, a press) moves it.
     */
    public record Setting(String key, float initial, float min, float max, float step) {
        /** {@code value} within what it may be; its initial value if it isn't a number. */
        public float bounded(float value) {
            return Float.isFinite(value) ? Mth.clamp(value, min, max) : initial;
        }

        /** Where {@code value} lies from min (0) to max (1), as a knob shows it. */
        public float fraction(float value) {
            return (value - min) / (max - min);
        }

        float load(CompoundTag tag) {
            return tag.getFloat(key);
        }

        void save(CompoundTag tag, float value) {
            tag.putFloat(key, value);
        }
    }

    public static final Setting MASTER = new Setting("Master", 0.8f, 0, 1, 0.05f);
    public static final Setting BRIGHTNESS = new Setting("Brightness", 1, 0, 1, 0.05f);
    public static final Setting RANGE = new Setting("Range", 32, 2, MAX_RANGE, 1);
    public static final Setting SPREAD = new Setting("Spread", 12, 1, 70, 1);
    public static final Setting SPEED = new Setting("Speed", 1, 0.05f, 4, 0.05f);
    public static final Setting PAN = new Setting("Pan", 0, -180, 180, 1);
    public static final Setting TILT = new Setting("Tilt", 35, -85, 85, 1);
    /** Flashes a second; 0 is a steady light. */
    public static final Setting STROBE = new Setting("Strobe", 0, 0, 10, 1);
    /** How much of each flash's time a strobing light is lit. */
    private static final double STROBE_DUTY = 0.4;
    /** How many of a group's lights are lit at once, chosen again on each beat; 0 for all. Not with a strobe. */
    public static final Setting CHASE = new Setting("Chase", 0, 0, 3, 1);

    /** One of a few ways a group does something, shown by its own lang key. Saved and sent as its ordinal. */
    public interface Mode {
        /** Its name's key, after {@code create_media_systems.lighting.}. */
        String key();
    }

    public enum Motion implements Mode {
        FIXED("motion.0"), SWEEP("motion.1"), CIRCLE("motion.2"), TRACK("motion.3");

        private final String key;

        Motion(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }

    public enum Pattern implements Mode {
        SPOT("pattern.0"), BEAM("pattern.1"), LASER_RAY("pattern.2"), LASER_FAN("pattern.3"), LASER_SHEET("pattern.4"),
        /** A wide cone fading from its middle out, with hardly a beam in the air. Last: a saved one is its ordinal. */
        WASH("pattern.5");

        private final String key;

        Pattern(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        /** Whether it is a cone of light (a spot, a beam or a wash), not a laser's rays. */
        public boolean cone() {
            return this == SPOT || this == BEAM || this == WASH;
        }

        /** The spread (degrees) its cone opens to, for a group's {@code spread}: a beam's is held narrow, a wash's wide. */
        public double coneSpread(double spread) {
            return switch (this) {
                case BEAM -> Math.min(spread, 2);
                case WASH -> Mth.clamp(spread * 2, 40, 100);
                default -> spread;
            };
        }
    }

    public enum Mapping implements Mode {
        SEQUENCE("mapping.0"), SPLIT("mapping.1"), GRADIENT("mapping.2"), PER_FIXTURE("mapping.3");

        private final String key;

        Mapping(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }

    public enum Band implements Mode {
        ALL("band.0"), BASS("band.1"), MID("band.2"), HIGH("band.3");

        private final String key;

        Band(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }

    /** Whose colours a group shows: its own, or its video source's. Shown as on (video) or off. */
    public enum ColorMode {
        OWN, VIDEO
    }

    /** {@code values}' member with ordinal {@code id}, the nearest if there is none, as a saved or sent one may not be. */
    public static <E extends Enum<E>> E byId(E[] values, int id) {
        return values[Mth.clamp(id, 0, values.length - 1)];
    }

    /** The member {@code step} places on from {@code current}, round the end. */
    public static <E extends Enum<E>> E cycle(E current, int step) {
        E[] values = current.getDeclaringClass().getEnumConstants();
        return values[Math.floorMod(current.ordinal() + step, values.length)];
    }

    /**
     * The console's built-in presets, keyed by their lang key: each replaces a group's effect (its motion, pattern,
     * audio response, colour cycle and spread, and its strobe off), keeping its colours and where it points.
     */
    public enum Preset {
        FIXED("preset.0", 0x36C9EE, Motion.FIXED, Pattern.SPOT, false, false, 12),
        SWEEP("preset.1", 0x9F66EF, Motion.SWEEP, Pattern.BEAM, true, false, 12),
        CYCLE("preset.2", 0xFFC75C, Motion.SWEEP, Pattern.BEAM, true, true, 12),
        FAN("preset.3", 0x32D999, Motion.CIRCLE, Pattern.LASER_FAN, true, false, 40),
        SHEET("preset.4", 0xEE6C9E, Motion.SWEEP, Pattern.LASER_SHEET, true, true, 40),
        TRACK("preset.5", 0x5C8AFF, Motion.TRACK, Pattern.SPOT, false, false, 30);

        public final String key;
        /** Its key's colour on the console. */
        public final int cue;
        final Motion motion;
        final Pattern pattern;
        final boolean audio;
        final boolean colorCycle;
        final float spread;

        Preset(String key, int cue, Motion motion, Pattern pattern, boolean audio, boolean colorCycle, float spread) {
            this.key = key;
            this.cue = cue;
            this.motion = motion;
            this.pattern = pattern;
            this.audio = audio;
            this.colorCycle = colorCycle;
            this.spread = spread;
        }
    }

    public final Group[] groups = new Group[GROUPS];
    public int selectedGroup;
    public int selectedColor;
    public boolean enabled = true;
    public float master = MASTER.initial();

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
        master = MASTER.bounded(master);
        selectedGroup = Mth.clamp(selectedGroup, 0, GROUPS - 1);
        selectedColor = Mth.clamp(selectedColor, 0, COLORS - 1);
        for (Group group : groups) {
            group.normalize();
        }
    }

    public static final class Group implements GroupView {
        public final int[] colors = DEFAULT_COLORS.clone();
        public int colorCount = 2;
        public LightingSettings.Mapping mapping = LightingSettings.Mapping.SEQUENCE;
        public ColorMode colorMode = ColorMode.OWN;
        public LightingSettings.Motion motion = LightingSettings.Motion.FIXED;
        public LightingSettings.Pattern pattern = LightingSettings.Pattern.SPOT;
        public LightingSettings.Band audioBand = LightingSettings.Band.ALL;
        public boolean audio;
        public boolean colorCycle;
        public float brightness = BRIGHTNESS.initial();
        public float range = RANGE.initial();
        public float spread = SPREAD.initial();
        public float speed = SPEED.initial();
        public float pan = PAN.initial();
        public float tilt = TILT.initial();
        public float strobe = STROBE.initial();
        public int chase;
        public UUID target;

        /** Takes {@code preset}'s effect; a tracking one follows {@code player}, if given. */
        public void preset(Preset preset, UUID player) {
            motion = preset.motion;
            pattern = preset.pattern;
            audio = preset.audio;
            colorCycle = preset.colorCycle;
            colorMode = ColorMode.OWN;
            audioBand = LightingSettings.Band.ALL;
            speed = SPEED.initial();
            spread = preset.spread;
            strobe = STROBE.initial();
            chase = 0;
            if (preset.motion == LightingSettings.Motion.TRACK && player != null) {
                target = player;
            }
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray("Colors", colors.clone());
            tag.putInt("ColorCount", colorCount);
            tag.putInt("Mapping", mapping.ordinal());
            tag.putInt("ColorMode", colorMode.ordinal());
            tag.putInt("Motion", motion.ordinal());
            tag.putInt("Pattern", pattern.ordinal());
            tag.putInt("AudioBand", audioBand.ordinal());
            tag.putBoolean("Audio", audio);
            tag.putBoolean("ColorCycle", colorCycle);
            BRIGHTNESS.save(tag, brightness);
            RANGE.save(tag, range);
            SPREAD.save(tag, spread);
            SPEED.save(tag, speed);
            PAN.save(tag, pan);
            TILT.save(tag, tilt);
            STROBE.save(tag, strobe);
            tag.putInt("Chase", chase);
            if (target != null) {
                tag.putUUID("Target", target);
            }
            return tag;
        }

        public void load(CompoundTag tag) {
            int[] saved = tag.getIntArray("Colors");
            System.arraycopy(saved, 0, colors, 0, Math.min(COLORS, saved.length));
            colorCount = tag.getInt("ColorCount");
            mapping = byId(LightingSettings.Mapping.values(), tag.getInt("Mapping"));
            colorMode = byId(ColorMode.values(), tag.getInt("ColorMode"));
            motion = byId(LightingSettings.Motion.values(), tag.getInt("Motion"));
            pattern = byId(LightingSettings.Pattern.values(), tag.getInt("Pattern"));
            audioBand = byId(LightingSettings.Band.values(), tag.getInt("AudioBand"));
            audio = tag.getBoolean("Audio");
            colorCycle = tag.getBoolean("ColorCycle");
            brightness = BRIGHTNESS.load(tag);
            range = RANGE.load(tag);
            spread = SPREAD.load(tag);
            speed = SPEED.load(tag);
            pan = PAN.load(tag);
            tilt = TILT.load(tag);
            strobe = STROBE.load(tag);
            chase = tag.getInt("Chase");
            target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
            normalize();
        }

        public void normalize() {
            for (int i = 0; i < COLORS; i++) {
                colors[i] &= 0xFFFFFF;
            }
            colorCount = Mth.clamp(colorCount, 1, COLORS);
            brightness = BRIGHTNESS.bounded(brightness);
            range = RANGE.bounded(range);
            spread = SPREAD.bounded(spread);
            speed = SPEED.bounded(speed);
            pan = PAN.bounded(pan);
            tilt = TILT.bounded(tilt);
            strobe = STROBE.bounded(strobe);
            chase = (int) CHASE.bounded(chase);
        }

        /** The colour it is showing in slot {@code slot}'s place (the selected one, say). */
        public int color(int slot) {
            return colors[Mth.clamp(slot, 0, COLORS - 1)];
        }

        /** Whether it shows its video source's colours now, a video being seen ({@code video}). */
        public boolean videoColors(boolean video) {
            return colorMode == ColorMode.VIDEO && video;
        }

        @Override
        public int colorCount() {
            return colorCount;
        }

        @Override
        public boolean colorCycle() {
            return colorCycle;
        }

        @Override
        public boolean audio() {
            return audio;
        }

        @Override
        public GroupView.Band audioBand() {
            return GroupView.Band.valueOf(audioBand.name());
        }

        @Override
        public GroupView.Motion motion() {
            return GroupView.Motion.valueOf(motion.name());
        }

        @Override
        public GroupView.Pattern pattern() {
            return GroupView.Pattern.valueOf(pattern.name());
        }

        @Override
        public GroupView.Mapping mapping() {
            return GroupView.Mapping.valueOf(mapping.name());
        }

        @Override
        public float brightness() {
            return brightness;
        }

        @Override
        public float range() {
            return range;
        }

        @Override
        public float spread() {
            return spread;
        }

        @Override
        public float speed() {
            return speed;
        }

        @Override
        public float pan() {
            return pan;
        }

        @Override
        public float tilt() {
            return tilt;
        }

        @Override
        public float strobe() {
            return strobe;
        }

        @Override
        public int chase() {
            return chase;
        }

        @Override
        @Nullable
        public UUID target() {
            return target;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled);
        MASTER.save(tag, master);
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
        master = MASTER.load(tag);
        selectedGroup = tag.getInt("SelectedGroup");
        selectedColor = tag.getInt("SelectedColor");
        ListTag list = tag.getList("Groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(GROUPS, list.size()); i++) {
            groups[i].load(list.getCompound(i));
        }
        normalize();
    }

    public Group selected() {
        return groups[selectedGroup];
    }

    /**
     * Whether the light {@code order}th of {@code count} is among the {@code chase} lit on the beat numbered
     * {@code beat} (any number, the same on every light of a group for one beat): always, at 0.
     */
    public static boolean chaseLit(int chase, int order, int count, long beat) {
        if (chase <= 0 || chase >= count) {
            return true;
        }
        // The first chase places of a shuffle of the lights, the same for every light that asks on this beat.
        int[] lights = new int[count];
        for (int i = 0; i < count; i++) {
            lights[i] = i;
        }
        java.util.Random random = new java.util.Random(beat * 0x9E3779B97F4A7C15L);
        for (int i = 0; i < chase; i++) {
            int pick = i + random.nextInt(count - i);
            int swap = lights[i];
            lights[i] = lights[pick];
            lights[pick] = swap;
            if (lights[i] == order) {
                return true;
            }
        }
        return false;
    }

    /** Whether a light strobing {@code strobe} times a second is lit at {@code seconds}: always, at 0. */
    public static boolean strobeLit(double seconds, float strobe) {
        return strobe <= 0 || Mth.positiveModulo(seconds * strobe, 1) < STROBE_DUTY;
    }

    public static int palette(int column, int row) {
        float x = Mth.clamp(column, 0, 7) / 7f;
        float y = Mth.clamp(row, 0, 7) / 7f;
        int top = Math.round(Math.min(1, (1 - x) * 2) * 255) << 8 | Math.round(Math.min(1, x * 2) * 255);
        int bottom = 0xFF0000 | Math.round((1 - x) * 140) << 8 | Math.round(x * 255);
        return Rgb.mix(top, bottom, y);
    }

    public static int color(Group group, int order, int count, double seconds, int leftColor, int rightColor,
                            boolean video) {
        boolean videoColors = group.videoColors(video);
        int n = videoColors ? 2 : group.colorCount;
        double position = switch (group.mapping) {
            case SPLIT -> Math.min(n - 1, Math.max(0, order) * n / Math.max(1, count));
            case GRADIENT -> count <= 1 ? 0 : Mth.clamp(order / (double) (count - 1), 0, 1) * (n - 1);
            default -> Math.floorMod(order, n);
        };
        if (group.colorCycle) {
            position += seconds * group.speed / 2;
        }
        int index = Math.floorMod((int) Math.floor(position), n);
        int next = (index + 1) % n;
        int a = videoColors ? (index == 0 ? leftColor : rightColor) : group.colors[index];
        int b = videoColors ? (next == 0 ? leftColor : rightColor) : group.colors[next];
        return Rgb.mix(a, b, (float) (position - Math.floor(position)));
    }
}
