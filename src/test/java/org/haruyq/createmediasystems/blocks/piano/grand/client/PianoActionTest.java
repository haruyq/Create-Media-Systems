package org.haruyq.createmediasystems.blocks.piano.grand.client;

import org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoState;
import org.junit.jupiter.api.Test;

import static org.haruyq.createmediasystems.blocks.piano.grand.PianoEvent.*;
import static org.junit.jupiter.api.Assertions.*;

class PianoActionTest {
    private static final long MS = 1_000_000, START = 1_000_000_000L;
    private final PianoState keys = new PianoState();
    private final PianoAction action = new PianoAction();

    @Test void aHardBlowLandsFirstAndAHeldHammerWaitsInCheckUntilItsKeyRises() {
        action.advance(keys, START);
        play(press(60, 127), 0);
        play(press(36, 20), 0);
        run(0, 17);
        assertEquals(1, action.hammer(60), .1f);
        assertTrue(action.hammer(36) < .2f, "the soft bass hammer is still on its way");
        assertTrue(action.ring(60) > .9f);
        assertEquals(0, action.ring(36));

        run(17, 200);
        assertEquals(PianoAction.CHECK, action.hammer(60), 1e-4f);
        assertEquals(PianoAction.CHECK, action.hammer(36), 1e-4f);
        assertEquals(1, action.key(60));
        assertEquals(1, action.damper(60));
        assertTrue(action.ring(36) > 0 && action.ring(36) < action.ring(60));

        play(release(60, 0), 200);
        run(200, 700);
        assertEquals(0, action.hammer(60));
        assertEquals(0, action.key(60));
        assertEquals(0, action.damper(60));
        assertEquals(0, action.ring(60), "its damper has stopped the string");
        assertTrue(action.ring(36) > 0, "the held bass note still sounds");
    }

    @Test void aKeyLetGoBeforeItsHammerLandsStillSoundsItsString() {
        action.advance(keys, START);
        play(press(72, 40), 0);
        play(release(72, 0), 3);
        float highest = 0;
        for (int ms = 4; ms <= 300; ms += 2) {
            action.advance(keys, START + ms * MS);
            highest = Math.max(highest, action.hammer(72));
        }
        assertTrue(highest > .95f, "the hammer reached " + highest);
        assertEquals(0, action.hammer(72));
        assertEquals(0, action.key(72));
    }

    @Test void theDamperPedalFreesEveryStringAndHalfPedalOnlyFeathersTheDampers() {
        action.advance(keys, START);
        play(control(DAMPER, 40), 0);
        run(0, 200);
        assertTrue(action.damper(21) > 0 && action.damper(21) < .5f);
        assertEquals(40 / 127f, action.pedal(DAMPER), 1e-4f);

        play(control(DAMPER, 127), 200);
        play(control(SOFT, 127), 200);
        run(200, 400);
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) assertEquals(1, action.damper(note));
        assertEquals(1, action.pedal(DAMPER));
        assertEquals(1, action.pedal(SOFT));
        assertEquals(0, action.pedal(SOSTENUTO));
    }

    private void play(PianoEvent event, int ms) {
        keys.apply(event);
        action.accept(event, START + ms * MS);
    }

    /** Frames every five milliseconds, as a fast client would draw them. */
    private void run(int from, int to) {
        for (int ms = from + 5; ms < to; ms += 5) action.advance(keys, START + ms * MS);
        action.advance(keys, START + to * MS);
    }
}
