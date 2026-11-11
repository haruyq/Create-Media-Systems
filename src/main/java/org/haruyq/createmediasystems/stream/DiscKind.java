package org.haruyq.createmediasystems.stream;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a media item is (see MediaItem): how many links it holds, the tallest picture it plays, and which slots take it.
 * A new kind of disc is one more constant here; the devices only ask it.
 */
public enum DiscKind {
    CUSTOM_DISC(1, 0, Slot.JUKEBOX, Slot.DISC_TRAY, Slot.PORTABLE),
    DVD(16, 480, Slot.PICTURE, Slot.DISC_TRAY, Slot.PORTABLE),
    BLU_RAY(64, 1080, Slot.PICTURE, Slot.DISC_TRAY, Slot.PORTABLE),
    CD(24, 0, Slot.DISC_TRAY),
    CASSETTE(12, 0, Slot.TAPE_DECK);

    public final int entries;
    /** The tallest picture (pixels) it plays; 0 for sound alone. */
    public final int pictureHeight;
    private final Set<Slot> slots;

    DiscKind(int entries, int pictureHeight, Slot first, Slot... rest) {
        this.entries = entries;
        this.pictureHeight = pictureHeight;
        this.slots = EnumSet.of(first, rest);
    }

    public boolean hasPicture() {
        return pictureHeight > 0;
    }

    public boolean fits(Slot slot) {
        return slots.contains(slot);
    }

    public enum Slot {
        /** Shows its picture on displays, and plays its sound. */
        PICTURE,
        /** A disc tray: plays any disc's sound. */
        DISC_TRAY,
        TAPE_DECK,
        /** Plays a disc's one link, as a jukebox does. */
        JUKEBOX,
        /** Carried or thrown about: plays an item's first link, or the jukebox song of a disc that is no media item. */
        PORTABLE
    }
}
