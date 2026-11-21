package org.haruyq.createmediasystems.items.instrument.client;

import java.util.ArrayDeque;
import java.util.Arrays;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import static org.haruyq.createmediasystems.items.instrument.InstrumentState.*;
import static org.haruyq.createmediasystems.playback.Pcm.RATE;

/** Six individually picked strings sampled by the bridge, middle and neck pickups of the model. */
final class ElectricStrings {
    private static final int SIZE = 512, PARTIALS = 16;
    private static final double[][] SINE = new double[PARTIALS][SIZE];
    private static final double[] PICKUPS = {.068, .153, .245};
    static {
        for (int h = 1; h <= PARTIALS; h++)
            for (int i = 0; i < SIZE; i++) SINE[h-1][i] = Math.sin(Math.TAU*h*i/SIZE);
    }

    private record Pick(long time, int string, int pitch, int velocity) {}
    private record Controls(int pickup, int tone, int bend, int volume) {}
    private final ArrayDeque<Pick> picks = new ArrayDeque<>();
    private final Voice[] voices = new Voice[6];
    private final int[] requested = new int[6];
    private volatile Controls controls = new Controls(0,127,CENTER_BEND,127);
    private long cursor;
    private double brightness, outGain;

    ElectricStrings() {
        Arrays.fill(requested,-1);
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice(i);
    }

    synchronized void update(InstrumentState state, int event, long now) {
        int tone = switch (state.pickup()) {
            case 0 -> state.bridgeTone();
            case 1 -> (state.bridgeTone()+state.neckTone())/2;
            default -> state.neckTone();
        };
        controls = new Controls(state.pickup(),tone,state.bend(),state.volume());
        if (picks.size() > 256) {
            picks.clear();
            Arrays.fill(requested,-1);
            for (int s = 0; s < 6; s++) picks.add(new Pick(now,s,-1,0));
            cursor = 0;
        }
        for (int s = 0; s < 6; s++) {
            int pitch = state.stringPitch(s);
            if (requested[s] != pitch || pitch >= 0 && command(event) == NOTE && second(event)>0 && first(event)==pitch) {
                picks.add(new Pick(now,s,pitch,pitch < 0 ? 0 : state.velocity(pitch)));
                requested[s] = pitch;
            }
        }
    }

    synchronized void render(float[] out, int frames) {
        Controls c = controls;
        double bendRatio = Math.pow(2,(c.bend-CENTER_BEND)/(8192.0*6));
        double filter = -Math.expm1(-Math.TAU*(650+5500*Math.pow(c.tone/127.0,1.7))/RATE);
        if (cursor == 0 && !picks.isEmpty()) cursor = picks.getFirst().time;
        for (Voice v : voices) if (v.level > .0001 && c.pickup != v.pickup) v.waveform(c.pickup);
        for (int i = 0; i < frames; i++) {
            while (!picks.isEmpty() && picks.getFirst().time <= cursor) {
                var p = picks.removeFirst();
                if (p.pitch < 0) voices[p.string].release = true;
                else voices[p.string].pick(p.pitch,p.velocity,c.pickup);
            }
            double dry = 0;
            for (Voice v : voices) dry += v.sample(bendRatio);
            brightness += filter*(dry-brightness);
            outGain += .006*(c.volume/127.0-outGain);
            out[i] = (float)(brightness*outGain*.28);
            cursor += 1_000_000_000L/RATE;
        }
        if (picks.isEmpty()) cursor = 0;
    }

    private static final class Voice {
        final int string;
        final double[][] wave = new double[3][SIZE+1];
        final double sustain;
        int note, pickup, age;
        double phase, step, level, body, edge, preceding, last;
        boolean release;

        Voice(int string) {
            this.string = string;
            sustain = Math.exp(-1/((1.95-.16*string)*RATE));
        }

        void pick(int pitch, int velocity, int selector) {
            note = pitch;
            waveform(selector);
            step = SIZE*440*Math.pow(2,(pitch-69)/12.0)/RATE;
            preceding = last;
            phase = 0;
            age = 0;
            release = false;
            level = Math.pow(velocity/127.0,.70);
            body = .85*level;
            edge = level*(.28+.82*level);
        }

        void waveform(int selector) {
            pickup = selector;
            for (var band : wave) Arrays.fill(band,0);
            double length = Math.pow(2,-InstrumentState.fret(string,note)/12.0);
            for (int h = 1; h <= PARTIALS; h++) {
                double harmonic = Math.PI*h/length;
                int pos = selector/2;
                double sensor = Math.sin(harmonic*PICKUPS[pos]);
                if ((selector&1)!=0) sensor = .5*(sensor+Math.sin(harmonic*PICKUPS[pos+1]));
                double coefficient = Math.sin(harmonic*(.19-.009*string))*sensor/h;
                double[] band = wave[h<=3 ? 0 : h<=8 ? 1 : 2];
                for (int i = 0; i < SIZE; i++) band[i] += SINE[h-1][i]*coefficient;
            }
            for (var band : wave) band[SIZE]=band[0];
        }

        double sample(double bend) {
            if (level < .00005) return 0;
            int index = (int)phase;
            double t = phase-index;
            double value = level*lookup(0,index,t)+body*lookup(1,index,t)+edge*lookup(2,index,t);
            phase = (phase+step*bend)%SIZE;
            double damper = release && age > RATE/64 ? .994 : 1;
            level *= sustain*damper;
            body *= .99994*damper;
            edge *= .99961*damper;
            double attack = Math.min(1,++age/(RATE*.0025));
            last = preceding*(1-attack)+value*attack;
            return last;
        }

        double lookup(int band, int i, double t) { return wave[band][i]+t*(wave[band][i+1]-wave[band][i]); }
    }
}
