package org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SynthTest {
    private static SoundFont font;

    @BeforeAll
    static void readTheBundledBank() throws IOException {
        try (InputStream in = SynthTest.class.getResourceAsStream(
                "/assets/create_media_systems/keyboard/a88mkii/generaluser_gs.sf3")) {
            font = SoundFont.read(ByteBuffer.wrap(in.readAllBytes()));
        }
    }

    private static int note(boolean on, int channel, int key, int velocity) {
        return Synth.message(on ? Synth.NOTE_ON : Synth.NOTE_OFF, channel, key, velocity);
    }

    /** Renders {@code seconds} of {@code synth}, both sides mixed. */
    private static float[] render(Synth synth, double seconds) {
        int blocks = (int) (seconds * Synth.RATE / Synth.BLOCK);
        float[] left = new float[blocks * Synth.BLOCK], right = new float[left.length];
        // In pieces, as it is played: what a note needs is decoded while the first ones render.
        for (int block = 0; block < blocks; block += 8) {
            synth.render(left, right, block * Synth.BLOCK, Math.min(8, blocks - block));
        }
        for (int i = 0; i < left.length; i++) {
            left[i] = (left[i] + right[i]) / 2;
        }
        return left;
    }

    private static double loudness(float[] audio, double fromSeconds, double toSeconds) {
        double sum = 0;
        int from = (int) (fromSeconds * Synth.RATE), to = Math.min(audio.length, (int) (toSeconds * Synth.RATE));
        for (int i = from; i < to; i++) {
            sum += audio[i] * audio[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    /** How much of {@code audio} is at {@code hertz} (Goertzel), against all of it. */
    private static double share(float[] audio, double hertz) {
        double coefficient = 2 * Math.cos(2 * Math.PI * hertz / Synth.RATE), s1 = 0, s2 = 0, energy = 0;
        for (float sample : audio) {
            double s0 = sample + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
            energy += sample * sample;
        }
        return (s1 * s1 + s2 * s2 - coefficient * s1 * s2) / (energy * audio.length / 2);
    }

    @Test
    void theBundledBankIsGeneralMidiWithDrumKits() {
        for (int program = 0; program < 128; program++) {
            assertNotNull(font.name(0, program), "program " + program);
            assertTrue(font.preset(0, program).length > 0, "program " + program);
        }
        assertTrue(font.name(0, 0).contains("Piano"), font.name(0, 0));
        assertTrue(font.preset(SoundFont.DRUMS, 0).length > 0);
        // A variation or kit the bank lacks plays the plain one.
        assertEquals(font.preset(0, 5), font.preset(99, 5));
        assertEquals(font.preset(SoundFont.DRUMS, 0), font.preset(SoundFont.DRUMS, 99));
    }

    @Test
    void whatIsNoBankIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SoundFont.read(ByteBuffer.wrap(new byte[64])));
    }

    @Test
    void aPianoKeySoundsAtItsPitchAcrossAllEightyEight() {
        for (int key : new int[]{21, 45, 69, 93, 108}) {
            Synth synth = new Synth(font);
            synth.send(note(true, 0, key, 100));
            float[] audio = render(synth, 0.8);
            // A piano's lowest strings sound their overtones more than their fundamental: A0 is told by its octave.
            double hertz = 440 * Math.pow(2, (Math.max(key, 33) - 69) / 12.0);
            assertTrue(loudness(audio, 0, 0.1) > 0.01, "key " + key + " is heard");
            // Its loudest pitch within a semitone is its own, give or take a piano's stretched tuning.
            float[] struck = Arrays.copyOf(audio, Synth.RATE / 5);
            int nearest = 0;
            for (int cents = -100; cents <= 100; cents += 5) {
                if (share(struck, hertz * Math.pow(2, cents / 1200.0)) > share(struck, hertz * Math.pow(2, nearest / 1200.0))) {
                    nearest = cents;
                }
            }
            assertTrue(Math.abs(nearest) <= 40, "key " + key + " is " + nearest + " cents off");
        }
    }

    @Test
    void aKeyLetGoDiesAwayUnlessThePedalHoldsIt() {
        Synth dry = new Synth(font), pedalled = new Synth(font);
        pedalled.send(Synth.message(Synth.CONTROL, 0, 64, 127));
        for (Synth synth : new Synth[]{dry, pedalled}) {
            synth.send(Synth.message(Synth.CONTROL, 0, 91, 0));
            synth.send(note(true, 0, 60, 100));
            render(synth, 0.3);
            synth.send(note(false, 0, 60, 0));
        }
        float[] let = render(dry, 1.5), held = render(pedalled, 1.5);
        double ringing = loudness(held, 1, 1.5);
        assertTrue(ringing > 0.01, "held: " + ringing);
        assertTrue(loudness(let, 1, 1.5) < ringing / 30, "let go: " + loudness(let, 1, 1.5));
        pedalled.send(Synth.message(Synth.CONTROL, 0, 64, 0));
        assertTrue(loudness(render(pedalled, 1.5), 1, 1.5) < ringing / 30);
    }

    @Test
    void itFallsSilentOnceNothingRings() {
        Synth synth = new Synth(font);
        assertTrue(synth.silent());
        synth.send(note(true, 0, 60, 100));
        assertFalse(synth.silent());
        render(synth, 0.2);
        synth.send(note(false, 0, 60, 0));
        render(synth, 10);
        assertTrue(synth.silent());
    }

    @Test
    void aProgramChangeAndABendChangeWhatAKeyPlays() {
        Synth organ = new Synth(font), bent = new Synth(font);
        organ.send(Synth.message(Synth.PROGRAM, 0, 19, 0));
        organ.send(note(true, 0, 69, 100));
        render(organ, 0.5);
        // An organ holds its level where a piano has decayed.
        Synth piano = new Synth(font);
        piano.send(note(true, 0, 69, 100));
        render(piano, 0.5);
        assertTrue(loudness(render(organ, 3), 2.5, 3) > 4 * loudness(render(piano, 3), 2.5, 3));

        // Bent all the way up: two semitones, a B for an A.
        bent.send(Synth.message(Synth.PROGRAM, 0, 19, 0));
        bent.send(Synth.message(Synth.BEND, 0, 127, 127));
        bent.send(note(true, 0, 69, 100));
        float[] audio = render(bent, 0.8);
        assertTrue(share(audio, 493.88) > 20 * share(audio, 440));
    }

    @Test
    void theTenthChannelPlaysDrumsAndAClosedHiHatCutsAnOpenOne() {
        Synth synth = new Synth(font);
        synth.send(note(true, Synth.DRUM_CHANNEL, 46, 110));
        render(synth, 0.05);
        Synth cut = new Synth(font);
        cut.send(note(true, Synth.DRUM_CHANNEL, 46, 110));
        render(cut, 0.05);
        cut.send(note(true, Synth.DRUM_CHANNEL, 42, 20));
        float[] open = render(synth, 0.4), closed = render(cut, 0.4);
        assertTrue(loudness(open, 0.15, 0.4) > 0.005, "open: " + loudness(open, 0.15, 0.4));
        assertTrue(loudness(closed, 0.15, 0.4) < loudness(open, 0.15, 0.4) / 3);
    }
}
