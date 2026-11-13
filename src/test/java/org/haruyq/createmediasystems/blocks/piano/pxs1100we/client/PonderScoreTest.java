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
        var held = new boolean[2][128];
        var cursor = score.cursor();
        var model = KeyboardLayout.PX;
        int last = -1;
        for (var cue : score.cues()) {
            assertTrue(cue.frame() > last);
            last = cue.frame();
            for (var event : cue.events()) {
                boolean attack = event.command() == KeyboardMidi.ON && event.value() > 0;
                if (attack || event.command() == KeyboardMidi.OFF || event.command() == KeyboardMidi.ON) {
                    int note = event.key();
                    assertEquals(note, model.keys.get(note - KeyboardLayout.FIRST).note());
                    held[event.channel()][note] = attack;
                    if (attack) attacks[event.channel()]++;
                }
            }
            cursor.advance(cue.frame() - 1, events -> {
                throw new AssertionError("A note was applied before its PCM frame");
            });
            cursor.advance(cue.frame(), events -> events.forEach(keys::apply));
            for (var key : model.keys) {
                int note = key.note();
                assertEquals(held[0][note] || held[1][note], keys.pressed(note),
                        "Wrong key at MIDI note " + note + ", frame " + cue.frame());
            }
        }
        assertEquals(175, attacks[0]);
        assertEquals(187, attacks[1]);
        for (int note = KeyboardLayout.FIRST; note <= KeyboardLayout.LAST; note++) assertFalse(keys.pressed(note));
    }

    @Test
    void theShortestNotesAreNotRoundedToGameTicks() {
        var score = PonderScore.load();
        int[] down = new int[128];
        int eighthSeconds = 0;
        for (var cue : score.cues()) {
            for (var event : cue.events()) {
                if (event.channel() != 0) continue;
                if (event.command() == KeyboardMidi.ON && event.value() > 0) down[event.key()] = cue.frame();
                if (event.command() == KeyboardMidi.OFF && cue.frame() - down[event.key()] == KeyboardSynth.RATE / 8) {
                    eighthSeconds++;
                }
            }
        }
        assertEquals(16, eighthSeconds);
    }
}
