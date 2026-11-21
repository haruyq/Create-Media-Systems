package org.haruyq.createmediasystems.items.instrument;

import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.haruyq.createmediasystems.items.instrument.MetalZoneBlock.Knob;

/** Each pot position is stored from 0 through 100. The EQ pots are flat at 50. */
public record MetalZoneSettings(int level, int dist, int low, int high, int middle, int midFreq) {
    public static final MetalZoneSettings DEFAULT = new MetalZoneSettings(55, 55, 50, 50, 50, 50);

    public MetalZoneSettings {
        level = clamp(level);
        dist = clamp(dist);
        low = clamp(low);
        high = clamp(high);
        middle = clamp(middle);
        midFreq = clamp(midFreq);
    }

    static int clamp(int value) {
        return Math.clamp(value, 0, 100);
    }

    public int value(Knob knob) {
        return switch (knob) {
            case LEVEL -> level;
            case DIST -> dist;
            case LOW -> low;
            case HIGH -> high;
            case MIDDLE -> middle;
            case MID_FREQ -> midFreq;
        };
    }

    public MetalZoneSettings with(Knob knob, int position) {
        return switch (knob) {
            case LEVEL -> new MetalZoneSettings(position, dist, low, high, middle, midFreq);
            case DIST -> new MetalZoneSettings(level, position, low, high, middle, midFreq);
            case LOW -> new MetalZoneSettings(level, dist, position, high, middle, midFreq);
            case HIGH -> new MetalZoneSettings(level, dist, low, position, middle, midFreq);
            case MIDDLE -> new MetalZoneSettings(level, dist, low, high, position, midFreq);
            case MID_FREQ -> new MetalZoneSettings(level, dist, low, high, middle, position);
        };
    }

    public static double eqDb(double position) {
        return .30 * (position - 50);
    }

    public static double midHertz(double position) {
        return 200 * Math.pow(25, position / 100);
    }

    public String readout(Knob knob) {
        int position = value(knob);
        return switch (knob) {
            case LEVEL, DIST -> position + "%";
            case LOW, HIGH, MIDDLE -> String.format(Locale.ROOT, "%+.1f dB", eqDb(position));
            case MID_FREQ -> Math.round(midHertz(position)) + " Hz";
        };
    }

    void save(CompoundTag tag) {
        for (Knob knob : Knob.values()) tag.putInt(knob.name(), value(knob));
    }

    static MetalZoneSettings load(CompoundTag tag) {
        var settings = DEFAULT;
        for (Knob knob : Knob.values()) {
            if (tag.contains(knob.name(), Tag.TAG_ANY_NUMERIC)) settings = settings.with(knob, tag.getInt(knob.name()));
        }
        return settings;
    }
}
