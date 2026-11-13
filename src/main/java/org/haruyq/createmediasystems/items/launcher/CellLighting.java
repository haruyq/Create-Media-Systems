package org.haruyq.createmediasystems.items.launcher;

public final class CellLighting {
    private CellLighting() {}

    /** The glow of a cell {@code charge} (0..1) full. */
    public static float strength(float charge) {
        return charge <= 0 ? 0 : .35f + .65f * (float) Math.sqrt(Math.min(1, charge));
    }
}
