package org.haruyq.createmediasystems.blocks.component.m700;

/**
 * A cable between two units of one stack, plugged in at either end's socket on the back with an audio cable. The
 * four flat system cables each join a unit to the amplifier (its socket there is in the cable's colour, so none fits
 * another's): without it the unit is dead. The line cable carries the disc player's sound to the amplifier: without
 * it a disc plays, unheard.
 */
public enum Cable {
    TUNER(Unit.TUNER, 0x2B2B2E),
    EQUALIZER(Unit.EQUALIZER, 0xDCDCD4),
    DISC(Unit.DISC_PLAYER, 0xC23A32),
    DECK(Unit.CASSETTE_DECK, 0xD9B93A),
    LINE(Unit.DISC_PLAYER, 0x3A3A3E);

    public final Unit unit;
    /** Its plugs' colour (RGB). */
    public final int color;

    Cable(Unit unit, int color) {
        this.unit = unit;
        this.color = color;
    }

    public int bit() {
        return 1 << ordinal();
    }

    /** The system cable of {@code unit}; null for the amplifier, which has none of its own. */
    public static Cable system(Unit unit) {
        for (Cable cable : values()) {
            if (cable.unit == unit && cable != LINE) {
                return cable;
            }
        }
        return null;
    }
}
