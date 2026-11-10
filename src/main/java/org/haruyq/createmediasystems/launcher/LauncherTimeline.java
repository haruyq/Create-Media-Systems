package org.haruyq.createmediasystems.launcher;

/**
 * Deterministic, allocation-free (apart from the returned record) reload/recoil animation.
 * Every stage uses a smoothstep transition so the latch, tray, disc and charging handle
 * never teleport at stage boundaries. Works in both first and third person.
 */
public final class LauncherTimeline {
    public static final int RELOAD_TICKS = 80;
    public static final int SHOT_TICKS = 10;

    private LauncherTimeline() {}

    public record Frame(float pitch, float yaw, float roll, float x, float y, float z,
                        float latch, float tray, float disc, float bolt, float flywheel,
                        float recoil, float supportHand, float cellOut, float glow) {}

    public static float clamp(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
    public static float smooth(float start, float end, float time) {
        float x = clamp((time - start) / (end - start));
        return x * x * (3.0f - 2.0f * x);
    }

    public static Frame sample(float reloadAge, float shotAge) {
        boolean reloading = reloadAge >= 0.0f && reloadAge < RELOAD_TICKS;
        float lower = reloading ? smooth(0, 9, reloadAge) * (1f - smooth(70, 80, reloadAge)) : 0f;
        float latch = reloading ? smooth(4, 14, reloadAge) * (1f - smooth(63, 73, reloadAge)) : 0f;
        // The bottom doors must clear the complete, unscaled cell before it moves.
        float tray = reloading ? smooth(6, 16, reloadAge) * (1f - smooth(63, 73, reloadAge)) : 0f;
        float disc = reloading ? smooth(32, 50, reloadAge) : 0f;
        float bolt = reloading ? smooth(70, 75, reloadAge) * (1f - smooth(76, 80, reloadAge)) : 0f;
        float support = reloading ? smooth(10, 16, reloadAge) * (1f - smooth(55, 63, reloadAge)) : 0f;
        float cellOut = reloading ? smooth(16, 28, reloadAge) : 0f;
        float recoil = shotAge >= 0 && shotAge < SHOT_TICKS
                ? smooth(0, 1.25f, shotAge) * (1f - smooth(2.5f, SHOT_TICKS, shotAge)) : 0f;
        float flywheel = reloading ? 0.1f + (1f - smooth(63, 80, reloadAge)) * 0.45f : recoil;
        float sway = lower * (float)Math.sin(reloadAge * 0.10f) * 0.006f;
        float glow = reloading ? latch * (.72f + .28f * (float)Math.pow(Math.sin(reloadAge * .45), 2)) : 0;
        return new Frame(lower * 12f + recoil * 1.4f, lower * 25f, lower * 22f,
                -lower * .24f + sway, lower * .18f + recoil * .018f,
                -lower * .30f + recoil * .018f,
                latch, tray, disc, bolt, flywheel, recoil, support, cellOut, glow);
    }
}
