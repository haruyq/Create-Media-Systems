package org.haruyq.createmediasystems.items.instrument.client;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.haruyq.createmediasystems.items.instrument.GuitarFrame;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuitarInputTest {
    private static short[] pluck(int block, double... notes) {
        short[] pcm = new short[GuitarFrame.SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            double t = (block * pcm.length + i) / 48000.0, sum = 0;
            for (double note : notes) {
                double phase = Math.TAU * 440 * Math.pow(2,(note-69)/12) * t;
                sum += Math.sin(phase) + .36*Math.sin(2*phase) + .19*Math.sin(3*phase) + .12*Math.sin(4*phase);
            }
            pcm[i] = (short)(sum * 24000 / (Math.max(1,notes.length)*1.7));
        }
        return pcm;
    }

    private static Set<Integer> notes(byte[] frame) {
        var found = new TreeSet<Integer>();
        for (int s = 0; s < 6; s++) if (frame[3*s+1] != 0)
            found.add((frame[3*s]&127) + InstrumentState.openString(s));
        return found;
    }

    @Test void transcribesTheFundamentalAcrossAllTwentyTwoFretsWithoutPlayingItsHarmonics() {
        for (int note = 40; note <= 86; note++) {
            var tracker = new GuitarTracker();
            byte[] result = null;
            for (int i = 0; i < 10; i++) result = tracker.analyze(pluck(i,note));
            assertEquals(Set.of(note), notes(result), "MIDI " + note);
        }
    }

    @Test void aChordUsesDistinctPlayableStringsAndReleasesOnSilence() {
        var tracker = new GuitarTracker();
        byte[] chord = null;
        for (int i = 0; i < 9; i++) chord = tracker.analyze(pluck(i,43,52,60));
        assertEquals(Set.of(43,52,60),notes(chord));
        assertEquals(3,chord[0]&127);
        assertEquals(2,chord[6]&127);
        assertEquals(1,chord[12]&127);
        for (int i = 0; i < 4; i++) chord = tracker.analyze(new short[GuitarFrame.SAMPLES]);
        assertTrue(notes(chord).isEmpty());
    }

    @Test void sixStringsInAnOpenEChordKeepTheirOwnOverlappingHarmonics() {
        var tracker = new GuitarTracker();
        byte[] chord = null;
        for (int i = 0; i < 12; i++) chord = tracker.analyze(pluck(i,40,47,52,56,59,64));
        assertEquals(Set.of(40,47,52,56,59,64), notes(chord));
        int[] expected = {0,2,2,1,0,0};
        for (int s = 0; s < expected.length; s++) assertEquals(expected[s], chord[3*s]&127);
    }

    @Test void aContinuousStringBendStaysOnTheOriginalFret() {
        var tracker = new GuitarTracker();
        double phase = 0;
        byte[] result = null;
        for (int block=0; block<21; block++) {
            var pcm = new short[GuitarFrame.SAMPLES];
            for (int i=0; i<pcm.length; i++) {
                double semitones = Math.clamp((block+i/(double)pcm.length-6)/7,0,1);
                phase += Math.TAU*440*Math.pow(2,(67+semitones-69)/12)/48000;
                pcm[i]=(short)(11000*(Math.sin(phase)+.19*Math.sin(phase*2)));
            }
            result=tracker.analyze(pcm);
        }
        assertEquals(Set.of(67),notes(result));
        assertTrue(result[17] >= 43, "bend on the high E string: "+Arrays.toString(result));
    }

    @Test void selectedInputAndIndependentNetworkBlocksKeepTheWaveform() {
        var input = new byte[GuitarFrame.SAMPLES*4];
        var expected = pluck(8,43,52,60);
        for (int i=0; i<expected.length; i++) {
            input[i*4+1]=80;
            input[i*4+2]=(byte)expected[i]; input[i*4+3]=(byte)(expected[i]>>8);
        }
        var mono=new short[expected.length];
        assertTrue(GuitarInput.channel(input,2,1,1,mono)>6000);
        assertArrayEquals(expected,mono);
        var enc=new GuitarPcm();
        enc.encode(pluck(1,40),new byte[18]);
        byte[] frame=enc.encode(mono,new byte[18]);
        assertTrue(GuitarFrame.valid(frame));
        var decoded=new GuitarPcm().decode(frame);
        double error=0, energy=0;
        for (int i=0;i<decoded.length;i++) {
            error+=Math.pow(mono[i]-decoded[i],2); energy+=(double)mono[i]*mono[i];
        }
        assertTrue(10*Math.log10(energy/error)>25);
        frame[0]=23; assertFalse(GuitarFrame.valid(frame));
        assertFalse(GuitarFrame.valid(new byte[GuitarFrame.BYTES-1]));
    }

    @Test void driveAddsHarmonicsToTheActualLineInputAndEachAmpClipsDifferently() {
        for (boolean distortion : new boolean[]{false, true}) {
            double previous = -1;
            for (int drive : new int[]{0, 64, 127}) {
                float[] out = guitarOutput(distortion, drive, true);
                double fundamental = harmonic(out, 1);
                assertTrue(fundamental > .1);
                double third = harmonic(out, 3) / fundamental;
                assertTrue(third > previous + .01, "third harmonic at drive " + drive + ": " + third);
                previous = third;
                if (drive == 127) {
                    double second = harmonic(out, 2) / fundamental;
                    if (distortion) assertTrue(second < .002 && third > .25);
                    else assertTrue(second > .02 && third < .25);
                }
            }
        }
    }

    @Test void bothAmpChannelsAndDriveAlsoShapeThePickedMidiString() {
        float[] light = guitarOutput(false, 0, false);
        float[] driven = guitarOutput(false, 127, false);
        float[] distorted = guitarOutput(true, 127, false);
        double driveDifference = 0, modeDifference = 0;
        for (int i = 0; i < light.length; i++) {
            driveDifference += Math.abs(light[i] - driven[i]);
            modeDifference += Math.abs(driven[i] - distorted[i]);
        }
        assertTrue(driveDifference / light.length > .05);
        assertTrue(modeDifference / light.length > .025);
    }

    private static float[] guitarOutput(boolean distortion, int drive, boolean lineIn) {
        var state = new InstrumentState(Kind.STRATOCASTER);
        state.apply(InstrumentState.control(InstrumentState.AMP, distortion ? 127 : 0));
        state.apply(InstrumentState.control(InstrumentState.DRIVE, drive));
        int message = lineIn ? 0 : InstrumentState.note(57, 108);
        if (!lineIn) state.apply(message);
        var guitar = new GuitarSound();
        guitar.update(state, message, 1_000_000);
        var input = new short[GuitarFrame.SAMPLES];
        for (int i = 0; i < input.length; i++) input[i] = (short) (11000 * Math.sin(5 * Math.TAU * i / input.length));
        var out = new float[input.length];
        for (int i = 0; i < 8; i++) {
            if (lineIn) guitar.offer(input, true);
            guitar.render(out, out.length);
        }
        assertEquals(!lineIn, state.sounding());
        return out;
    }

    private static double harmonic(float[] signal, int multiple) {
        double real = 0, imaginary = 0;
        for (int i = 0; i < signal.length; i++) {
            double phase = 5 * multiple * Math.TAU * i / signal.length;
            real += signal[i] * Math.cos(phase);
            imaginary += signal[i] * Math.sin(phase);
        }
        return 2 * Math.hypot(real, imaginary) / signal.length;
    }

    @Test void everySynthesizedElectricGuitarPitchSurvivesANoteOffInTheSameTick() {
        for (int note = 40; note <= 86; note++) {
            var strings = new ElectricStrings();
            var state = new InstrumentState(Kind.STRATOCASTER);
            for (int v : new int[]{96,0}) {
                int message=InstrumentState.note(note,v);
                state.apply(message);
                strings.update(state,message,1_000_000);
            }
            var out = new float[960];
            strings.render(out,out.length);
            double energy=0;
            for (float f:out) energy+=f*f;
            assertTrue(energy>.01,"Electric guitar note "+note);
        }
    }
}
