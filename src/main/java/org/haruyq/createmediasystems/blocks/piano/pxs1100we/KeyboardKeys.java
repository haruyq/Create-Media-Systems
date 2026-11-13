package org.haruyq.createmediasystems.blocks.piano.pxs1100we;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi.*;

public final class KeyboardKeys {
    private final int[][] down = new int[16][128], sustained = new int[16][128], latched = new int[16][128];
    private final int[][] controllers = new int[16][128];
    private final int[] bends = new int[16];
    private final int[] programmedBanks = new int[16], programs = new int[16];
    private final boolean[] expression = new boolean[16];

    public KeyboardKeys() {
        Arrays.fill(bends, 8192);
        Arrays.fill(programs, -1);
    }

    public boolean pressed(int note) {
        for (int[] keys : down) if (keys[note] != 0) return true;
        return false;
    }

    public boolean pedal() {
        return pedalAmount(SUSTAIN) > 0 || pedalAmount(SOSTENUTO) > 0 || pedalAmount(SOFT) > 0;
    }

    public int pedalAmount(int control) {
        int amount = 0;
        for (int[] channel : controllers) amount = Math.max(amount, channel[control]);
        return amount;
    }

    boolean active() {
        if (pedal()) return true;
        for (int[] channel : down) for (int velocity : channel) if (velocity != 0) return true;
        return false;
    }

    public void apply(KeyboardMidi event) {
        int channel = event.channel(), note = event.key(), value = event.value();
        if (event.command() == ON && value != 0) {
            down[channel][note] = value;
            sustained[channel][note] = 0;
        } else if (event.command() == OFF || event.command() == ON) {
            if (down[channel][note] != 0) {
                sustained[channel][note] = controllers[channel][SUSTAIN] >= 64 ? down[channel][note] : 0;
                down[channel][note] = 0;
            }
        } else if (event.command() == BEND) {
            bends[channel] = event.bend();
        } else if (event.command() == PROGRAM) {
            programs[channel] = note;
            programmedBanks[channel] = controllers[channel][0] << 7 | controllers[channel][32];
        } else if (event.command() == CONTROL) {
            if (note == SILENCE) {
                Arrays.fill(down[channel], 0);
                Arrays.fill(sustained[channel], 0);
                Arrays.fill(latched[channel], 0);
            } else if (note == NOTES_OFF) {
                if (controllers[channel][SUSTAIN] >= 64) {
                    for (int n = 0; n < 128; n++) {
                        if (down[channel][n] != 0) sustained[channel][n] = down[channel][n];
                    }
                }
                Arrays.fill(down[channel], 0);
            } else if (note == RESET) {
                Arrays.fill(controllers[channel], 0);
                Arrays.fill(sustained[channel], 0);
                Arrays.fill(latched[channel], 0);
                bends[channel] = 8192;
                expression[channel] = false;
            } else {
                int before = controllers[channel][note];
                controllers[channel][note] = value;
                if (note == 11) expression[channel] = true;
                if (note == SUSTAIN && value < 64) Arrays.fill(sustained[channel], 0);
                if (note == SOSTENUTO && value < 64) Arrays.fill(latched[channel], 0);
                if (note == SOSTENUTO && value >= 64 && before < 64) {
                    System.arraycopy(down[channel], 0, latched[channel], 0, 128);
                }
            }
        }
    }

    public List<KeyboardMidi> snapshot() {
        var events = new ArrayList<KeyboardMidi>();
        for (int channel = 0; channel < 16; channel++) {
            if (channel != 0 && programs[channel] >= 0) {
                int bank = programmedBanks[channel];
                events.add(new KeyboardMidi(CONTROL | channel, 0, bank >> 7));
                events.add(new KeyboardMidi(CONTROL | channel, 32, bank & 127));
                events.add(new KeyboardMidi(PROGRAM | channel, programs[channel], 0));
            }
            if (expression[channel]) events.add(new KeyboardMidi(CONTROL | channel, 11, controllers[channel][11]));
            for (int cc : new int[] {SUSTAIN, SOFT}) {
                if (controllers[channel][cc] != 0) events.add(new KeyboardMidi(CONTROL | channel, cc, controllers[channel][cc]));
            }
            if (bends[channel] != 8192) events.add(new KeyboardMidi(BEND | channel, bends[channel] & 127, bends[channel] >> 7));
            if (controllers[channel][SOSTENUTO] >= 64) {
                for (int n = 0; n < 128; n++) {
                    if (latched[channel][n] != 0) events.add(new KeyboardMidi(ON | channel, n, latched[channel][n]));
                }
                events.add(new KeyboardMidi(CONTROL | channel, SOSTENUTO, controllers[channel][SOSTENUTO]));
            }
            for (int n = 0; n < 128; n++) {
                int velocity = down[channel][n], ringing = sustained[channel][n], held = latched[channel][n];
                if (velocity != 0) {
                    if (held == 0) events.add(new KeyboardMidi(ON | channel, n, velocity));
                } else if (ringing != 0 || held != 0) {
                    if (held == 0) events.add(new KeyboardMidi(ON | channel, n, ringing));
                    events.add(new KeyboardMidi(OFF | channel, n, 0));
                }
            }
        }
        return events;
    }
}
