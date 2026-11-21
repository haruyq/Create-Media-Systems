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

    @Test void guitarChordsFitSixStringsAndRestoreTheSameFretsForANewListener() {
        var guitar = new InstrumentState(Kind.STRATOCASTER);
        int[] chord = {40, 47, 52, 55, 59, 64};
        for (int note : chord) assertTrue(guitar.apply(note(note, 92)));
        assertFalse(guitar.apply(note(39, 92)));
        assertFalse(guitar.apply(note(87, 92)));
        for (int s = 0; s < chord.length; s++) assertEquals(chord[s], guitar.stringPitch(s));
        guitar.apply(note(86, 110));
        guitar.apply(bend(13500));
        guitar.apply(control(VOLUME, 96));
        guitar.apply(control(PICKUP, 127));
        guitar.apply(control(TONE_NECK, 40));
        guitar.apply(control(TONE_BRIDGE, 102));
        guitar.apply(control(AMP, 127));
        guitar.apply(control(DRIVE, 106));
        var listener = new InstrumentState(Kind.STRATOCASTER);
        guitar.snapshot().forEach(listener::apply);
        assertEquals(22, InstrumentState.fret(5, listener.stringPitch(5)));
        assertEquals(13500, listener.bend());
        assertEquals(4, listener.pickup());
        assertEquals(96, listener.volume());
        assertEquals(40, listener.neckTone());
        assertEquals(102, listener.bridgeTone());
        assertEquals(new GuitarAmp(true, 106), listener.amp());
        for (int s = 0; s < chord.length; s++) assertEquals(guitar.stringPitch(s), listener.stringPitch(s));
        guitar.apply(note(86, 0));
        for (int note : chord) assertEquals(92, guitar.voicedVelocity(note));
    }

    @Test void onlyTheGuitarAcceptsTheTwoAmpChannelsAndDriveController() {
        var guitar = new InstrumentState(Kind.STRATOCASTER);
        assertEquals(GuitarAmp.DEFAULT, guitar.amp());
        for (int value = 0; value <= 127; value++) {
            assertTrue(guitar.apply(control(DRIVE, value)));
            assertTrue(guitar.apply(control(AMP, value)));
            assertEquals(value, guitar.amp().drive());
            assertEquals(value >= 64, guitar.amp().distortion());
            assertEquals(value >= 64 ? 30 : 29, guitar.program());
        }
        for (var kind : Kind.values()) {
            if (kind == Kind.STRATOCASTER) continue;
            var instrument = new InstrumentState(kind);
            assertFalse(instrument.apply(control(AMP, 127)));
            assertFalse(instrument.apply(control(DRIVE, 127)));
        }
    }

    @Test void windsPlayConcertPitchWithLastNotePriorityAndRestoreBreathAndNoteOrder() {
        for (var kind : new Kind[]{Kind.FLUTE, Kind.TRUMPET}) {
            var wind = new InstrumentState(kind);
            assertFalse(wind.apply(note(kind.first - 1, 80)));
            assertFalse(wind.apply(note(kind.last() + 1, 80)));
            wind.apply(note(67, 58));
            wind.apply(note(60, 103));
            assertEquals(0, wind.voicedVelocity(67));
            assertEquals(103, wind.voicedVelocity(60));
            wind.apply(control(BREATH, 0));
            var listener = new InstrumentState(kind);
            wind.snapshot().forEach(listener::apply);
            assertFalse(listener.sounding());
            assertEquals(60, listener.windPitch());
            listener.apply(note(60, 0));
            listener.apply(control(BREATH, 96));
            assertEquals(58, listener.voicedVelocity(67));
            listener.apply(control(RELEASE_KEYS, 0));
            assertEquals(-1, listener.windPitch());
            assertFalse(listener.sounding());
        }
    }
}
