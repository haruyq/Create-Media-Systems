package org.haruyq.createmediasystems.piano;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.haruyq.createmediasystems.piano.PianoEvent.*;

/** Key positions and damper state. Each renderer and sampler keeps its own copy of the same ordered events. */
public final class PianoState {
    private final int[] pressed = new int[KEYS];
    private final boolean[] sostenutoKeys = new boolean[KEYS];
    private int damper, sostenuto, soft;
    private int volume = 127, expression = 127;

    public boolean pressed(int note) { return pressed[note - FIRST_NOTE] != 0; }
    public int pedal(int controller) {
        return switch (controller) {
            case DAMPER -> damper;
            case SOSTENUTO -> sostenuto;
            case SOFT -> soft;
            default -> 0;
        };
    }
    public float output() { return (volume / 127f) * (expression / 127f); }

    /** The upper treble has no individual dampers. Sostenuto captures only keys held when it goes down. */
    public boolean rings(int note) {
        return pressed(note) || note >= 89 || damper >= 64 || sostenutoKeys[note - FIRST_NOTE];
    }

    public void apply(PianoEvent event) {
        int number = event.number(), value = event.value();
        switch (event.kind()) {
            case ON -> pressed[number - FIRST_NOTE] = value;
            case OFF -> pressed[number - FIRST_NOTE] = 0;
            case CONTROL -> {
                switch (number) {
                    case VOLUME -> volume = value;
                    case EXPRESSION -> expression = value;
                    case DAMPER -> damper = value;
                    case SOFT -> soft = value;
                    case SOSTENUTO -> {
                        if (value >= 64 && sostenuto < 64) {
                            for (int i = 0; i < KEYS; i++) sostenutoKeys[i] = pressed[i] != 0;
                        } else if (value < 64 && sostenuto >= 64) {
                            Arrays.fill(sostenutoKeys, false);
                        }
                        sostenuto = value;
                    }
                    case ALL_NOTES_OFF -> Arrays.fill(pressed, 0);
                    case ALL_SOUND_OFF -> {
                        Arrays.fill(pressed, 0);
                        Arrays.fill(sostenutoKeys, false);
                    }
                    case RESET_CONTROLLERS -> {
                        damper = sostenuto = soft = 0;
                        expression = 127;
                        Arrays.fill(sostenutoKeys, false);
                    }
                }
            }
        }
    }

    /** Introduces a new listener to the keys still down without sounding already released keys again. */
    List<PianoEvent> snapshot() {
        List<PianoEvent> events = new ArrayList<>();
        events.add(control(VOLUME, volume));
        events.add(control(EXPRESSION, expression));
        events.add(control(DAMPER, damper));
        events.add(control(SOFT, soft));
        for (int i = 0; i < KEYS; i++) {
            if (pressed[i] != 0 && sostenutoKeys[i]) events.add(new PianoEvent(Kind.ON, FIRST_NOTE + i, pressed[i]));
        }
        events.add(control(SOSTENUTO, sostenuto));
        for (int i = 0; i < KEYS; i++) {
            if (pressed[i] != 0 && !sostenutoKeys[i]) events.add(new PianoEvent(Kind.ON, FIRST_NOTE + i, pressed[i]));
        }
        return events;
    }
}
