package org.haruyq.createmediasystems.launcher;

/** Bounded RGB point-light math shared by item, hand and world presentation. */
public final class CellLighting {
    private CellLighting() {}

    public static float strength(float charge) {
        return charge <= 0 ? 0 : .35f + .65f * (float) Math.sqrt(Math.min(1, charge));
    }

    public static float falloff(float distance, float radius) {
        if (radius <= 0 || distance >= radius) return 0;
        float edge = 1 - Math.max(0, distance) / radius;
        return edge * edge;
    }

    public static int channel(int base, int lightChannel, float amount) {
        float blend = Math.clamp(amount * .9f, 0f, .95f);
        return Math.clamp(Math.round(base * (1 - blend * (1 - lightChannel / 255f))), 0, 255);
    }
}
