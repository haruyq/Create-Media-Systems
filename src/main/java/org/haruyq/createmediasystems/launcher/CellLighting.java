package org.haruyq.createmediasystems.launcher;

/** How brightly a cell glows, shared by its item, hand and world presentation. */
public final class CellLighting {
    private CellLighting() {}

    /** The glow of a cell {@code charge} (0..1) full: none when empty, at least a third when not. */
    public static float strength(float charge) {
        return charge <= 0 ? 0 : .35f + .65f * (float) Math.sqrt(Math.min(1, charge));
    }
}
