package org.haruyq.createmediasystems.piano.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.midi.ShortMessage;

import org.haruyq.createmediasystems.piano.PianoEvent;

import static org.haruyq.createmediasystems.piano.PianoEvent.*;

final class MidiDecoder {
    private final boolean[][] held = new boolean[16][KEYS];
    private final int[][] pedals = new int[3][16];
    private final int[] velocityPrefix = new int[16];
    private int channel = -1;

    MidiDecoder() { Arrays.fill(velocityPrefix, -1); }

    synchronized void channel(int channel) {
        this.channel = channel;
        clear();
    }

    private void clear() {
        for (var keys : held) Arrays.fill(keys, false);
        for (var pedal : pedals) Arrays.fill(pedal, 0);
        Arrays.fill(velocityPrefix, -1);
    }

    synchronized List<PianoEvent> decode(ShortMessage message) {
        if (message.getStatus() == ShortMessage.SYSTEM_RESET) {
            clear();
            return List.of(control(ALL_SOUND_OFF, 0), control(RESET_CONTROLLERS, 0));
        }
        if (message.getStatus() >= 0xF0) return List.of();
        int source = message.getChannel();
        if (channel >= 0 && source != channel) return List.of();
        int command = message.getCommand(), key = message.getData1(), value = message.getData2();
        if (command == ShortMessage.CONTROL_CHANGE && key == 88) {
            velocityPrefix[source] = value;
            return List.of();
        }
        int prefix = velocityPrefix[source];
        velocityPrefix[source] = -1;
        if (command == ShortMessage.CONTROL_CHANGE) return controller(source, key, value);
        if ((command != ShortMessage.NOTE_ON && command != ShortMessage.NOTE_OFF) || !isNote(key)) return List.of();

        int velocity = prefix >= 0 ? (value << 7) | prefix : value * 129;
        if (command == ShortMessage.NOTE_ON && value != 0) {
            held[source][key - FIRST_NOTE] = true;
            return List.of(new PianoEvent(Kind.ON, key, velocity));
        }
        held[source][key - FIRST_NOTE] = false;
        return stillHeld(key) ? List.of() : List.of(new PianoEvent(Kind.OFF, key, velocity));
    }

    private boolean stillHeld(int note) {
        for (boolean[] keys : held) if (keys[note - FIRST_NOTE]) return true;
        return false;
    }

    private List<PianoEvent> controller(int channel, int number, int value) {
        if (number == ALL_NOTES_OFF || number >= 124) {
            List<PianoEvent> released = new ArrayList<>();
            for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
                if (!held[channel][note - FIRST_NOTE]) continue;
                held[channel][note - FIRST_NOTE] = false;
                if (!stillHeld(note)) released.add(release(note, 64));
            }
            return released;
        }
        if (number == ALL_SOUND_OFF) {
            for (var keys : held) Arrays.fill(keys, false);
        } else if (number == RESET_CONTROLLERS) {
            for (var pedal : pedals) Arrays.fill(pedal, 0);
        }
        if (!acceptsControl(number)) return List.of();

        int pedalIndex = switch (number) {
            case DAMPER -> 0;
            case SOSTENUTO -> 1;
            case SOFT -> 2;
            default -> -1;
        };
        if (pedalIndex >= 0) {
            int[] positions = pedals[pedalIndex];
            positions[channel] = value;
            for (int position : positions) value = Math.max(value, position);
        }
        return List.of(control(number, value));
    }
}
