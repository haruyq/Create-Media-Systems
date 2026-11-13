package org.haruyq.createmediasystems.items.launcher;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The reload and recoil timeline: every stage of a reload by its ticks, the sounds it makes, and the deterministic,
 * allocation-free (apart from the returned record) animation sampled from it. Every stage uses a smoothstep
 * transition so the latch, tray, disc and charging handle never teleport at stage boundaries. Works in both first and
 * third person.
 */
public final class LauncherTimeline {
    public static final int RELOAD_TICKS = 80;
    public static final int SHOT_TICKS = 10;

    public record Stage(float start, float end) {
        /** 0 before the stage, 1 after it, smooth in between. */
        public float at(float age) { return smooth(start, end, age); }
    }

    /** The gun lowers and turns to the reloading hold, and comes back up. */
    public static final Stage LOWER = new Stage(0, 9), RAISE = new Stage(70, RELOAD_TICKS);
    public static final Stage LATCH_OPENS = new Stage(4, 14), LATCH_CLOSES = new Stage(63, 73);
    /** The bottom doors must clear the complete, unscaled cell before it moves; they close with the latch. */
    public static final Stage DOORS_OPEN = new Stage(6, 16);
    /** The support hand comes up to the well and goes back to the grip. */
    public static final Stage HAND_IN = new Stage(10, 16), HAND_OUT = new Stage(55, 63);
    public static final Stage OLD_CELL_OUT = new Stage(16, 28), NEW_CELL_IN = new Stage(32, 50);
    /** From here the hand holds the incoming cell, between the old one's leaving and the new one's entering. */
    public static final int INCOMING_AT = 30;
    public static final Stage FLYWHEEL_SLOWS = new Stage(63, RELOAD_TICKS);
    public static final Stage BOLT_BACK = new Stage(70, 75), BOLT_HOME = new Stage(76, RELOAD_TICKS);

    /** The sounds a reload makes, each at its tick, as the holder hears them. */
    public enum Cue {
        LATCH(12, .7f), OLD_CELL_OUT((int) LauncherTimeline.OLD_CELL_OUT.end(), 1.15f),
        NEW_CELL_IN((int) LauncherTimeline.NEW_CELL_IN.end(), 1.15f), CHARGE(74, 1.15f);

        public final int tick;
        public final float pitch;

        Cue(int tick, float pitch) {
            this.tick = tick;
            this.pitch = pitch;
        }

        /** The cue at reload age {@code age}, or null. */
        public static Cue at(long age) {
            for (Cue cue : values()) if (cue.tick == age) return cue;
            return null;
        }
    }

    private LauncherTimeline() {}

    public record Frame(float pitch, float yaw, float roll, float x, float y, float z,
                        float latch, float tray, float disc, float bolt, float flywheel,
                        float recoil, float supportHand, float cellOut, float glow) {}

    public static float clamp(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
    public static float smooth(float start, float end, float time) {
        float x = clamp((time - start) / (end - start));
        return x * x * (3.0f - 2.0f * x);
    }

    /** Ticks (with {@code partial}) since {@code launcher}'s reload started, or -1 when it isn't reloading. */
    public static float reloadAge(ItemStack launcher, Level level, float partial) {
        Long start = level == null ? null : launcher.get(LauncherFeature.LAUNCHER_RELOAD_START.get());
        return start == null ? -1 : level.getGameTime() - start + partial;
    }

    /** Ticks (with {@code partial}) since {@code launcher} last fired, or -1 when it never has. */
    public static float shotAge(ItemStack launcher, Level level, float partial) {
        Long shot = level == null ? null : launcher.get(LauncherFeature.LAUNCHER_SHOT_AT.get());
        return shot == null ? -1 : level.getGameTime() - shot + partial;
    }

    public static Frame sample(ItemStack launcher, Level level, float partial) {
        return sample(reloadAge(launcher, level, partial), shotAge(launcher, level, partial));
    }

    public static Frame sample(float reloadAge, float shotAge) {
        boolean reloading = reloadAge >= 0.0f && reloadAge < RELOAD_TICKS;
        float lower = reloading ? LOWER.at(reloadAge) * (1f - RAISE.at(reloadAge)) : 0f;
        float latch = reloading ? LATCH_OPENS.at(reloadAge) * (1f - LATCH_CLOSES.at(reloadAge)) : 0f;
        float tray = reloading ? DOORS_OPEN.at(reloadAge) * (1f - LATCH_CLOSES.at(reloadAge)) : 0f;
        float disc = reloading ? NEW_CELL_IN.at(reloadAge) : 0f;
        float bolt = reloading ? BOLT_BACK.at(reloadAge) * (1f - BOLT_HOME.at(reloadAge)) : 0f;
        float support = reloading ? HAND_IN.at(reloadAge) * (1f - HAND_OUT.at(reloadAge)) : 0f;
        float cellOut = reloading ? OLD_CELL_OUT.at(reloadAge) : 0f;
        float recoil = shotAge >= 0 && shotAge < SHOT_TICKS
                ? smooth(0, 1.25f, shotAge) * (1f - smooth(2.5f, SHOT_TICKS, shotAge)) : 0f;
        float flywheel = reloading ? 0.1f + (1f - FLYWHEEL_SLOWS.at(reloadAge)) * 0.45f : recoil;
        float sway = lower * (float)Math.sin(reloadAge * 0.10f) * 0.006f;
        float glow = reloading ? latch * (.72f + .28f * (float)Math.pow(Math.sin(reloadAge * .45), 2)) : 0;
        return new Frame(lower * 12f + recoil * 1.4f, lower * 25f, lower * 22f,
                -lower * .24f + sway, lower * .18f + recoil * .018f,
                -lower * .30f + recoil * .018f,
                latch, tray, disc, bolt, flywheel, recoil, support, cellOut, glow);
    }
}
