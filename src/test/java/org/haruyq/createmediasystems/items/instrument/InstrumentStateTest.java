package org.haruyq.createmediasystems.items.instrument;

import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.junit.jupiter.api.Test;

import static org.haruyq.createmediasystems.items.instrument.InstrumentState.*;
import static org.junit.jupiter.api.Assertions.*;

class InstrumentStateTest {
    @Test void melodicaKeysAndBreathAreIndependent() {
        var keys = new InstrumentState(Kind.MELODICA);
        assertFalse(keys.apply(note(52, 80)));
        assertTrue(keys.apply(note(53, 80)));
        assertEquals(80, keys.velocity(53));
        assertFalse(keys.sounding());
        assertTrue(keys.apply(control(BREATH, 108)));
        assertTrue(keys.sounding());
        assertEquals(22, keys.program());
        var tracker = new InstrumentState(Kind.MELODICA);
        keys.snapshot().forEach(tracker::apply);
        assertTrue(tracker.sounding());
        assertTrue(keys.apply(control(BREATH, 0)));
        assertFalse(keys.sounding());
        assertEquals(80, keys.velocity(53));
    }

    @Test void sustainKeepsReleasedNotesInLateListenerSnapshots() {
        var keys = new InstrumentState(Kind.KEYTAR);
        assertTrue(keys.apply(tone(5)));
        assertFalse(keys.apply(tone(8)));
        assertTrue(keys.apply(bend(10000)));
        assertTrue(keys.apply(control(SUSTAIN, 127)));
        assertFalse(keys.sounding());
        assertTrue(keys.apply(note(36, 94)));
        assertTrue(keys.apply(note(36, 0)));
        assertTrue(keys.sounding());
        assertEquals(0, keys.velocity(36));
        var tracker = new InstrumentState(Kind.KEYTAR);
        keys.snapshot().forEach(tracker::apply);
        assertTrue(tracker.sounding());
        assertEquals(10000, tracker.bend());
        assertEquals(62, tracker.program());
        assertTrue(tracker.apply(control(SUSTAIN, 0)));
        assertFalse(tracker.sounding());
    }
}
