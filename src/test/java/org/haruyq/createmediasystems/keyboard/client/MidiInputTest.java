package org.haruyq.createmediasystems.keyboard.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;

import org.haruyq.createmediasystems.keyboard.Controller;
import org.haruyq.createmediasystems.keyboard.Panel;

import org.junit.jupiter.api.Test;

class MidiInputTest {
    private final List<Integer> sent = new ArrayList<>();
    private final MidiInput.In in = new MidiInput.In(new Controller(new Panel(), new Controller.Out() {
        @Override
        public void send(int message) {
            if ((message & 0x80) != 0) {
                sent.add(message);
            }
        }

        @Override
        public void panelChanged() {
        }
    }));

    private void device(int command, int channel, int data1, int data2) throws InvalidMidiDataException {
        in.send(new ShortMessage(command, channel, data1, data2), -1);
    }

    @Test
    void aDevicesNotesPlayAtOnceOnTheZonesChannelWhateverChannelItSendsOn() throws InvalidMidiDataException {
        device(ShortMessage.NOTE_ON, 5, 60, 90);
        device(ShortMessage.NOTE_ON, 5, 60, 0);
        device(ShortMessage.NOTE_ON, 5, 62, 70);
        device(ShortMessage.NOTE_OFF, 5, 62, 40);
        assertEquals(List.of(Controller.message(Controller.NOTE_ON, 0, 60, 90), Controller.message(Controller.NOTE_OFF, 0, 60, 0),
                Controller.message(Controller.NOTE_ON, 0, 62, 70), Controller.message(Controller.NOTE_OFF, 0, 62, 0)), sent);
    }

    @Test
    void wheelsAndPedalsGoOnAsTheLatestOfEachWhenFlushed() throws InvalidMidiDataException {
        for (int value = 0; value < 100; value++) {
            device(ShortMessage.PITCH_BEND, 0, value, 64);
            device(ShortMessage.CONTROL_CHANGE, 0, 1, value);
        }
        device(ShortMessage.PITCH_BEND, 0, 0, 64);
        device(ShortMessage.CONTROL_CHANGE, 0, 64, 127);
        device(ShortMessage.CONTROL_CHANGE, 0, 0, 5);
        device(ShortMessage.CONTROL_CHANGE, 0, 123, 0);
        assertEquals(List.of(), sent);
        in.flush();
        assertEquals(Set.of(Controller.message(Controller.CONTROL, 0, 1, 99), Controller.message(Controller.CONTROL, 0, 64, 127),
                Controller.message(Controller.BEND, 0, 0, 64)), Set.copyOf(sent));
        in.flush();
        assertEquals(3, sent.size());
    }
}
