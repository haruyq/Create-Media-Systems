package org.haruyq.createmediasystems.items.instrument.client;

import java.util.Arrays;
import org.haruyq.createmediasystems.items.instrument.GuitarFrame;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.gervill.FFT;

/** Transcribes the clean pickup signal before the amp or pedals can introduce extra harmonics. */
final class GuitarTracker {
    private static final int RATE = 12000, WINDOW = 1024, N = 4096, STRINGS = GuitarFrame.STRINGS;
    private static final int FIRST = 40, LAST = 86;
    private static final double BIN = RATE / (double) N;
    private final FFT fft = new FFT(N, -1);
    private final double[] history = new double[WINDOW], hann = new double[WINDOW], work = new double[2*N];
    private final double[] magnitude = new double[N/2], remaining = new double[N/2];
    private final int[] pitches = new int[STRINGS], choice = new int[STRINGS], assignment = new int[STRINGS];
    private final double[] measured = new double[STRINGS], levels = new double[STRINGS];
    private final int[] base = new int[STRINGS], missed = new int[STRINGS], settled = new int[STRINGS];
    private final double[] previous = new double[STRINGS], envelope = new double[STRINGS];
    private int cursor, count, signalAge;
    private double bestCost, hand = 2, inputLevel;

    GuitarTracker() {
        Arrays.fill(base, -1);
        for (int i = 0; i < WINDOW; i++) hann[i] = .5 - .5 * Math.cos(Math.TAU * i / (WINDOW-1));
    }

    byte[] analyze(short[] pcm) {
        double power = 0;
        for (int i = 0; i < pcm.length; i += 4) {
            double x = (pcm[i] + pcm[i+1] + pcm[i+2] + pcm[i+3]) / 131072.0;
            power += x*x;
            history[cursor] = x;
            cursor = (cursor + 1) % WINDOW;
        }
        double rms = Math.sqrt(power / (pcm.length / 4));
        boolean transientAttack = rms > .004 && rms > inputLevel * 1.60 + .001;
        inputLevel = rms;
        count = 0;
        signalAge = rms > .0009 ? Math.min(4, signalAge+1) : 0;
        if (signalAge >= 4) detect();
        Arrays.fill(choice, -1);
        Arrays.fill(assignment, -1);
        bestCost = Double.POSITIVE_INFINITY;
        assign(0, 0, 0, 0, 0);
        byte[] result = new byte[GuitarFrame.FINGERING];
        int fretted = 0, sum = 0;
        for (int s = 0; s < STRINGS; s++) {
            int at = 3*s, n = assignment[s];
            if (n < 0) {
                if (++missed[s] >= 2) { base[s] = -1; envelope[s] = 0; settled[s] = 0; }
            } else {
                double pitch = measured[n], level = levels[n];
                boolean slide = settled[s] >= 2 && base[s] >= 0 && !transientAttack && Math.abs(pitch - previous[s]) < .68
                        && Math.abs(pitch - base[s]) <= 2.05;
                int next = slide ? base[s] : pitches[n];
                int fret = InstrumentState.fret(s, next);
                if (fret < 0 || fret > 22) next = pitches[n];
                boolean strike = base[s] != next || missed[s] > 0 || level > envelope[s]*1.65 + .012;
                if (next != base[s]) settled[s] = 0;
                if (Math.abs(pitch-next) < .28) settled[s] = Math.min(3, settled[s]+1);
                base[s] = next;
                previous[s] = pitch;
                missed[s] = 0;
                envelope[s] = level;
                if (strike) result[at] = (byte) 128;
            }
            if (base[s] < 0) continue;
            int fret = InstrumentState.fret(s, base[s]);
            result[at] |= (byte) fret;
            result[at+1] = (byte) Math.clamp(Math.round(180*Math.sqrt(envelope[s])), 1, 127);
            result[at+2] = (byte) Math.clamp(Math.round(50*(previous[s]-base[s])), -100, 100);
            if (fret > 0) { sum += fret; fretted++; }
        }
        if (fretted > 0) hand += .3*(sum/(double)fretted - hand);
        return result;
    }

    private void detect() {
        Arrays.fill(work, 0);
        for (int i = 0; i < WINDOW; i++) work[2*i] = hann[i]*history[(cursor+i)%WINDOW];
        fft.transform(work);
        double maximum = 0;
        for (int i = 1; i < magnitude.length; i++) {
            magnitude[i] = Math.hypot(work[2*i], work[2*i+1])*4/WINDOW;
            maximum = Math.max(maximum, magnitude[i]);
        }
        System.arraycopy(magnitude, 0, remaining, 0, magnitude.length);
        while (count < STRINGS) {
            int best = -1;
            double score = 0, frequency = 0;
            for (int bin = 25; bin < 425; bin++) {
                double peak = remaining[bin];
                if (peak < Math.max(.0015, maximum*.055) || magnitude[bin] < magnitude[bin-1]
                        || magnitude[bin] <= magnitude[bin+1]) continue;
                double hz = peakBin(bin)*BIN;
                int note = (int)Math.round(midi(hz));
                if (note < FIRST || note > LAST) continue;
                boolean duplicate = false;
                for (int n = 0; n < count; n++) if (pitches[n] == note) duplicate = true;
                if (duplicate) continue;
                double salience = peak;
                // When two fundamentals share partials, fit the lower one first. Its upper
                // partial must not consume the evidence for an independently played octave.
                for (int divisor = 2; divisor <= 4 && hz/divisor >= 80; divisor++) {
                    int lower = nearPeak(hz/divisor);
                    if (remaining[lower] > peak*.26 && Math.abs(peakBin(lower)*BIN*divisor-hz) < hz*.019) {
                        salience *= .58;
                        break;
                    }
                }
                for (int h = 2; h <= 6; h++) {
                    int harmonic = nearPeak(hz*h);
                    salience += Math.min(peak, remaining[harmonic]) / h;
                }
                if (salience > score) { score = salience; best = bin; frequency = hz; }
            }
            if (best < 0) break;
            double strength = remaining[best];
            double weightedHz = frequency * strength, weights = strength;
            for (int h = 1; h <= 18 && frequency*h < 5650; h++) {
                int bin = nearPeak(frequency*h);
                double harmonic = remaining[bin];
                double hz = peakBin(bin)*BIN/h;
                if (h <= 6 && Math.abs(hz-frequency) < frequency*.022) {
                    weightedHz += hz*harmonic; weights += harmonic;
                }
                // Subtract the measured lobe of this partial. A stronger overlapping octave can survive.
                double expected = h == 1 ? strength : strength / Math.pow(h, 1.18);
                double amplitude = Math.min(harmonic, expected);
                double center = h == 1 ? peakBin(best) : frequency*h/BIN;
                int radius = 2*N/WINDOW;
                for (int j = Math.max(1, bin-radius); j <= Math.min(magnitude.length-1,bin+radius); j++) {
                    double k = (j-center) * WINDOW / N;
                    double lobe = Math.abs(k) < .0001 ? 1 : Math.abs(Math.abs(k)-1) < .0001 ? .5
                            : Math.abs(Math.sin(Math.PI*k)/(Math.PI*k*(1-k*k)));
                    remaining[j] = Math.max(0, remaining[j] - amplitude*lobe);
                }
            }
            double pitch = midi(weightedHz / weights);
            int note = Math.clamp((int)Math.round(pitch), FIRST, LAST);
            int insert = count++;
            while (insert > 0 && pitches[insert-1] > note) {
                pitches[insert] = pitches[insert-1]; levels[insert] = levels[insert-1];
                measured[insert] = measured[insert-1]; insert--;
            }
            pitches[insert] = note; levels[insert] = strength; measured[insert] = pitch;
        }
    }

    private int nearPeak(double hz) {
        int center = (int)Math.round(hz/BIN);
        int radius = Math.max(2, Math.min(6, (int)(center*.007)));
        int best = Math.clamp(center, 1, magnitude.length-2);
        for (int j = Math.max(1,center-radius); j <= Math.min(magnitude.length-2,center+radius); j++)
            if (magnitude[j] > magnitude[best]) best = j;
        return best;
    }

    private double peakBin(int bin) {
        double a = Math.log(Math.max(1e-12,magnitude[bin-1])), b = Math.log(Math.max(1e-12,magnitude[bin]));
        double c = Math.log(Math.max(1e-12,magnitude[bin+1])), denominator = a - 2*b + c;
        return bin + (denominator < -1e-9 ? Math.clamp(.5*(a-c)/denominator,-.5,.5) : 0);
    }

    private static double midi(double hz) { return 69 + 12*Math.log(hz/440)/Math.log(2); }

    /** Ascending pitches on distinct playable strings, favoring the preceding position and a compact chord. */
    private void assign(int n, int firstString, int lo, int hi, double cost) {
        if (cost >= bestCost) return;
        if (n == count) {
            bestCost = cost;
            System.arraycopy(choice,0,assignment,0,STRINGS);
            return;
        }
        for (int s = firstString; s < STRINGS; s++) {
            int fret = InstrumentState.fret(s,pitches[n]);
            if (fret < 0 || fret > 22) continue;
            int low = fret == 0 ? lo : lo == 0 ? fret : Math.min(lo,fret);
            int high = Math.max(hi,fret);
            double movement = fret == 0 ? 0 : .55 + .12*fret + .40*Math.abs(fret-hand);
            if (base[s] >= 0 && Math.abs(previous[s]-measured[n]) < .75) movement *= .58;
            choice[s] = n;
            assign(n+1,s+1,low,high,cost+movement+Math.max(0,high-low-4)*1.5);
            choice[s] = -1;
        }
        assign(n+1,firstString,lo,hi,cost+40+80*levels[n]);
    }
}
