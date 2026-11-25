package org.haruyq.createmediasystems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnNotesTest {
    @Test
    void eachOwnNoteSkipsExactlyOneEchoAndOthersStillPlay() {
        var own = new OwnNotes();
        own.add("snare", 0);
        own.add("snare", 1);
        assertFalse(own.echo("kick", 2), "Another player's note was skipped");
        assertTrue(own.echo("snare", 3));
        assertTrue(own.echo("snare", 4));
        assertFalse(own.echo("snare", 5), "A third identical note was skipped after two own strikes");
    }

    @Test
    void aRejectedStrikeStopsSkippingAfterTheWindow() {
        var own = new OwnNotes();
        own.add("snare", 0);
        assertFalse(own.echo("snare", OwnNotes.WINDOW + 1));
    }
}
