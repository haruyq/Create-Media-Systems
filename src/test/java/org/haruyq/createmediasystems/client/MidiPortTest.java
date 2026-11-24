package org.haruyq.createmediasystems.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MidiPortTest {
    private static final MidiPort.Input A = new MidiPort.Input("A-88MKII|Roland|MIDI|0", "A-88MKII", null);
    private static final MidiPort.Input B = new MidiPort.Input("nanoKEY|KORG|MIDI|0", "nanoKEY", null);

    @Test
    void cyclesInputsThenOffAndAllOnlyWhereOffered() {
        var inputs = List.of(A, B);
        assertEquals(A.id(), MidiPort.next(inputs, "", false));
        assertEquals(B.id(), MidiPort.next(inputs, A.id(), false));
        assertEquals(MidiPort.OFF, MidiPort.next(inputs, B.id(), false));
        assertEquals(A.id(), MidiPort.next(inputs, MidiPort.OFF, false));
        assertEquals(A.id(), MidiPort.next(inputs, "", true));
        assertEquals("", MidiPort.next(inputs, MidiPort.OFF, true));
        assertEquals(A.id(), MidiPort.next(inputs, "gone|x|y|0", false));
    }

    @Test
    void picksByIdOrByAnOldNamePart() {
        assertTrue(MidiPort.picks(A.id(), A));
        assertFalse(MidiPort.picks(B.id(), A));
        assertTrue(MidiPort.picks("a-88", A));
        assertFalse(MidiPort.picks("", A));
        assertFalse(MidiPort.picks(MidiPort.OFF, new MidiPort.Input("Off|x|y|0", "Office Piano", null)));
    }
}
