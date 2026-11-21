package org.haruyq.createmediasystems.items.instrument.client;

import java.util.ArrayDeque;
import java.util.Arrays;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;

import static org.haruyq.createmediasystems.items.instrument.InstrumentState.*;

/** Converts a fingering to sounding pitches. The audio thread consumes timed changes one synthesis block at a time. */
final class InstrumentVoices {
    private record Change(long at, int midi) {}
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private final boolean[] playing = new boolean[128];
    private final long[] earliestRelease = new long[128];
    private final boolean[] releasing = new boolean[128];
    private long cursor, frame;
    private int expression = -1, volume = -1, brightness = -1, bend = -1;

    synchronized void update(InstrumentState state, int message, long now) {
        if (changes.size() > 512 || !changes.isEmpty() && now - changes.getFirst().at > 1_000_000_000L) {
            // A stalled sound device must resume from the current chord.
            changes.clear();
            changes.add(new Change(now, Synth.message(Synth.CONTROL, 0, Synth.ALL_SOUND_OFF, 0)));
            Arrays.fill(playing, false);
            expression = volume = brightness = bend = -1;
            cursor = 0;
        }
        if (state.kind.wind() && expression != state.breath()) {
            expression = state.breath();
            enqueue(now, Synth.CONTROL, Synth.EXPRESSION, expression);
        }
        if (state.kind == Kind.STRATOCASTER) {
            if (bend != state.bend()) {
                bend = state.bend();
                enqueue(now, Synth.BEND, bend & 127, bend >> 7);
            }
            if (volume != state.volume()) {
                volume = state.volume();
                enqueue(now, Synth.CONTROL, Synth.VOLUME, volume);
            }
            if (brightness != state.brightness()) {
                brightness = state.brightness();
                enqueue(now, Synth.CONTROL, Synth.BRIGHTNESS, brightness);
            }
        }
        for (int note = state.kind.first; note <= state.kind.last(); note++) {
            int velocity = state.voicedVelocity(note);
            if (velocity > 0 && (!playing[note] || command(message) == NOTE && first(message) == note && second(message) > 0)) {
                enqueue(now, Synth.NOTE_ON, note, velocity);
            } else if (velocity == 0 && playing[note]) {
                enqueue(now, Synth.NOTE_OFF, note, 0);
            }
            playing[note] = velocity != 0;
        }
    }

    private void enqueue(long at, int command, int a, int b) {
        changes.add(new Change(at, Synth.message(command, 0, a, b)));
    }

    synchronized void nextBlock(Synth synth) {
        if (cursor == 0 && !changes.isEmpty()) cursor = changes.getFirst().at;
        for (Change next; (next = changes.peekFirst()) != null && next.at <= cursor; ) {
            changes.removeFirst();
            int midi = next.midi, note = first(midi);
            switch (command(midi)) {
                case Synth.NOTE_ON -> {
                    releasing[note] = false;
                    earliestRelease[note] = frame + Synth.RATE / 64;
                }
                case Synth.NOTE_OFF -> {
                    // Note on/off can arrive in the same network tick or before the sound bank opens.
                    if (frame < earliestRelease[note]) {
                        releasing[note] = true;
                        continue;
                    }
                }
                case Synth.CONTROL -> {
                    if (note == Synth.ALL_SOUND_OFF) Arrays.fill(releasing, false);
                }
            }
            synth.send(midi);
        }
        for (int n = 0; n < releasing.length; n++) {
            if (releasing[n] && frame >= earliestRelease[n]) {
                synth.send(Synth.message(Synth.NOTE_OFF, 0, n, 0));
                releasing[n] = false;
            }
        }
        cursor = changes.isEmpty() ? 0 : cursor + Synth.BLOCK * 1_000_000_000L / Synth.RATE;
        frame += Synth.BLOCK;
    }
}
