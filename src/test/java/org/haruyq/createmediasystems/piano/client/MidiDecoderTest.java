package org.haruyq.createmediasystems.piano.client;

import javax.sound.midi.ShortMessage;
import org.haruyq.createmediasystems.piano.PianoEvent;
import org.haruyq.createmediasystems.piano.PianoState;
import org.junit.jupiter.api.Test;

import static javax.sound.midi.ShortMessage.*;
import static org.haruyq.createmediasystems.piano.PianoEvent.*;
import static org.junit.jupiter.api.Assertions.*;

class MidiDecoderTest {
    @Test void acceptsBothEndsOfAn88KeyKeyboardAndPreservesVelocityAcrossMidiClock() throws Exception {
        var midi = new MidiDecoder();
        midi.channel(3);
        assertTrue(midi.decode(new ShortMessage(NOTE_ON, 2, 21, 64)).isEmpty());
        assertTrue(midi.decode(new ShortMessage(CONTROL_CHANGE, 3, 88, 47)).isEmpty());
        assertTrue(midi.decode(new ShortMessage(TIMING_CLOCK)).isEmpty());
        assertEquals(new PianoEvent(Kind.ON, 21, (42 << 7) | 47),
                midi.decode(new ShortMessage(NOTE_ON, 3, 21, 42)).getFirst());
        assertEquals(release(21, 57), midi.decode(new ShortMessage(NOTE_OFF, 3, 21, 57)).getFirst());
        assertEquals(press(108, 127), midi.decode(new ShortMessage(NOTE_ON, 3, 108, 127)).getFirst());
        assertEquals(release(108, 0), midi.decode(new ShortMessage(NOTE_ON, 3, 108, 0)).getFirst());
        assertTrue(midi.decode(new ShortMessage(NOTE_ON, 3, 109, 70)).isEmpty());
    }

    @Test void sostenutoCapturesOnlyHeldNotesAndDoesNotReplaceTheOtherPedals() throws Exception {
        var midi = new MidiDecoder();
        var keys = new PianoState();
        midi.decode(new ShortMessage(NOTE_ON, 0, 60, 62)).forEach(keys::apply);
        midi.decode(new ShortMessage(CONTROL_CHANGE, 0, SOSTENUTO, 127)).forEach(keys::apply);
        midi.decode(new ShortMessage(NOTE_ON, 0, 64, 103)).forEach(keys::apply);
        midi.decode(new ShortMessage(NOTE_OFF, 0, 60, 64)).forEach(keys::apply);
        midi.decode(new ShortMessage(NOTE_OFF, 0, 64, 64)).forEach(keys::apply);
        assertFalse(keys.pressed(60));
        assertTrue(keys.rings(60));
        assertFalse(keys.rings(64));
        midi.decode(new ShortMessage(CONTROL_CHANGE, 0, SOFT, 115)).forEach(keys::apply);
        midi.decode(new ShortMessage(CONTROL_CHANGE, 0, DAMPER, 53)).forEach(keys::apply);
        assertEquals(115, keys.pedal(SOFT));
        assertEquals(53, keys.pedal(DAMPER));
        assertFalse(keys.rings(64));
        midi.decode(new ShortMessage(CONTROL_CHANGE, 0, DAMPER, 127)).forEach(keys::apply);
        assertTrue(keys.rings(64));
        midi.decode(new ShortMessage(CONTROL_CHANGE, 0, DAMPER, 0)).forEach(keys::apply);
        assertTrue(keys.rings(60));
        assertFalse(keys.rings(64));
        midi.decode(new ShortMessage(CONTROL_CHANGE, 0, RESET_CONTROLLERS, 0)).forEach(keys::apply);
        assertFalse(keys.rings(60));
        assertFalse(keys.rings(64));
        assertEquals(0, keys.pedal(SOFT));
    }
}
