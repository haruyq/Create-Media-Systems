package org.haruyq.createmediasystems.blocks.stagecustom.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class StageCustomAudioTest {
    private static final int CHUNK = 15 * Synth.BLOCK;
    private static final int PEDAL = 5, AFTER_PEDAL = 8, END = 12;
    private static SoundFont bank;

    @BeforeAll
    static void readTheBundledBank() throws IOException {
        try (var in = StageCustomAudioTest.class.getResourceAsStream("/assets/create_media_systems/keyboard/a88mkii/generaluser_gs.sf3")) {
            bank = SoundFont.read(ByteBuffer.wrap(in.readAllBytes()));
        }
    }

    @Test
    void closingTheHatStopsItsSoundFontTailWithoutCuttingOffTheRide() {
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

    private static double[] render(List<Integer> notes, boolean pedal) {
        var synth = StageCustomAudio.synth(bank);
        for (int note : notes) StageCustomAudio.hit(synth, note, 110);
        float[] left = new float[CHUNK], right = new float[CHUNK];
        var pcm = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN);
        double[] mono = new double[END * CHUNK];
        for (int offset = 0; offset < mono.length; offset += CHUNK) {
            if (pedal && offset == PEDAL * CHUNK) StageCustomAudio.hit(synth, 44, 100);
            StageCustomAudio.render(synth, left, right, CHUNK, pcm.clear());
            for (int i = 0; i < CHUNK; i++) mono[offset + i] = pcm.getShort(i * 2) / 32768.0;
        }
        return mono;
    }

    private static double square(double value) { return value * value; }
}
