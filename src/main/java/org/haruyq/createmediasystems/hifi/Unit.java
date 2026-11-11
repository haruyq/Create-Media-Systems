package org.haruyq.createmediasystems.hifi;

import net.minecraft.util.Mth;

/**
 * A component of a hi-fi stack (see HifiStackBlock): each its own item, stacked in one block in any order. Sizes are
 * in sixteenths of a block; a whole set is exactly one block tall.
 */
public enum Unit {
    AMPLIFIER("amplifier", "MA-700", 3.5f),
    EQUALIZER("equalizer", "MG-700", 2.5f),
    TUNER("tuner", "MT-700", 2.5f),
    DISC_PLAYER("disc_player", "MD-700", 3),
    CASSETTE_DECK("cassette_deck", "MC-700W", 4.5f);

    public static final float WIDTH = 12;
    public static final float DEPTH = 10;
    /** Where a stack facing south starts across its block, and where its back and front are. */
    public static final float LEFT = (16 - WIDTH) / 2;
    public static final float BACK = (16 - DEPTH) / 2;
    public static final float FRONT = BACK + DEPTH;
    public static final float ROOM = 16;
    /** The made-up maker whose badge every unit carries. */
    public static final String BRAND = "PIONEAR";

    /** What its item is called after {@code hifi_}. */
    public final String id;
    public final String model;
    public final float height;

    Unit(String id, String model, float height) {
        this.id = id;
        this.model = model;
        this.height = height;
    }

    public static Unit byId(int id) {
        return values()[Mth.clamp(id, 0, values().length - 1)];
    }
}
