package org.haruyq.createmediasystems.blocks.stagecustom.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardMidi;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.client.KeyboardSynth;
import org.haruyq.createmediasystems.playback.Pcm;
import org.junit.jupiter.api.Test;

final class StageCustomAudioTest {
    private static final int CHUNK = KeyboardSynth.FRAMES_PER_BUFFER;
    private static final int PEDAL = 5, AFTER_PEDAL = 8, END = 12;

    @Test
    void closingTheHatStopsItsSoundFontTailWithoutCuttingOffTheRide() throws Exception {
        var ride = render(List.of(51), false);
        var foot = render(List.of(), true);
        var footAndRide = render(List.of(51), true);
        var open = render(List.of(46, 51), false);
        var closed = render(List.of(46, 51), true);
        double openTail = 0, dampedTail = 0, rideTail = 0, rideError = 0;
        // Inspect the direct sample after the pedal has reached the synth and before room reflections arrive.
        for (int i = AFTER_PEDAL * CHUNK; i < ride.length; i++) {
            openTail += square(open[i] - ride[i]);
            dampedTail += square(closed[i] - footAndRide[i]);
            rideTail += square(ride[i]);
            rideError += square(footAndRide[i] - foot[i] - ride[i]);
        }
        assertTrue(openTail > .001, "An open hi-hat should have a tail to damp");
        assertTrue(dampedTail < openTail / 100, "The pedal should damp the direct open hi-hat sample");
        assertTrue(rideTail > .01 && rideError < rideTail / 100, "The pedal should leave the ride sounding");
    }

    private static double[] render(List<Integer> notes, boolean pedal) throws Exception {
        var settings = StageCustomAudio.settings();
        var initial = new ArrayList<KeyboardMidi>();
        for (int note : notes) StageCustomAudio.appendNote(initial, note, 110);
        byte[] stereo = new byte[CHUNK * 4];
        double[] mono = new double[END * CHUNK];
        try (var synth = KeyboardSynth.bundled(40).join()) {
            synth.apply(settings, true, initial);
            for (int offset = 0; offset < mono.length; offset += CHUNK) {
                if (pedal && offset == PEDAL * CHUNK) {
                    var events = new ArrayList<KeyboardMidi>();
                    StageCustomAudio.appendNote(events, 44, 100);
                    synth.apply(settings, false, events);
                }
                synth.read(stereo, CHUNK);
                for (int i = 0; i < CHUNK; i++) {
                    mono[offset + i] = .5 * (Pcm.sample(stereo, i, 2, 0) + Pcm.sample(stereo, i, 2, 1));
                }
            }
        }
        return mono;
    }

    private static double square(double value) { return value * value; }
}
