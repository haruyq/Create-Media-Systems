package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardKeys;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardLayout;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderScoreTest {
    @Test
    void bundledMidiKeepsBothHandsAndReleasesItsKeys() {
        var score = PonderScore.load();
        assertEquals(680, score.duration());
        var keys = new KeyboardKeys();
        int[] attacks = new int[2];
        int last = -1;
        for (var cue : score.cues()) {
            assertTrue(cue.tick() > last);
            last = cue.tick();
            for (var event : cue.events()) {
                keys.apply(event);
                if (event.command() == KeyboardMidi.ON && event.value() > 0) attacks[event.channel()]++;
            }
        }
        assertEquals(175, attacks[0]);
        assertEquals(187, attacks[1]);
        for (int note = KeyboardLayout.FIRST; note <= KeyboardLayout.LAST; note++) assertFalse(keys.pressed(note));
    }
}
