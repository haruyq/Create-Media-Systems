package org.haruyq.createmediasystems.keyboard.synth;

import static org.haruyq.createmediasystems.keyboard.synth.SoundFont.*;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.haruyq.createmediasystems.keyboard.synth.SoundFont.Modulator;
import org.haruyq.createmediasystems.keyboard.synth.SoundFont.Region;

/**
 * A General MIDI sound module playing a {@link SoundFont}: 16 channels (the tenth drums), fed MIDI channel messages
 * from any thread ({@link #send}) and rendered in stereo by one ({@link #render}). A message sounds from the next
 * render on, so how soon is up to how little is rendered at a time.
 */
public final class Synth {
    public static final int RATE = 48_000;
    /** Frames rendered at a time: envelopes, pitch and filters move once per block. */
    public static final int BLOCK = 32;
    public static final int NOTE_OFF = 0x80, NOTE_ON = 0x90, CONTROL = 0xB0, PROGRAM = 0xC0, PRESSURE = 0xD0, BEND = 0xE0;
    /** The channel that plays drum kits. */
    public static final int DRUM_CHANNEL = 9;
    /** Controllers: the ones a bank's modulators don't take care of are worked here. */
    public static final int BANK = 0, MODULATION = 1, DATA = 6, VOLUME = 7, PAN_POSITION = 10, EXPRESSION = 11,
            SUSTAIN_PEDAL = 64, SOSTENUTO_PEDAL = 66, SOFT_PEDAL = 67, RESONANCE_LEVEL = 71, RELEASE_TIME = 72,
            ATTACK_TIME = 73, BRIGHTNESS = 74, REVERB_SEND = 91, CHORUS_SEND = 93, ALL_SOUND_OFF = 120,
            RESET_CONTROLLERS = 121, ALL_NOTES_OFF = 123;

    private static final int VOICES = 96;
    private static final float MASTER = 1.4f, SECONDS = BLOCK / (float) RATE;
    /** How quiet (dB) a voice is when it is done. */
    private static final float SILENT = 96;
    private static final int WAITING = 0, RISING = 1, HELD = 2, FALLING = 3, SUSTAINED = 4, LET_GO = 5, DONE = 6;

    private final SoundFont font;
    private final ConcurrentLinkedQueue<Integer> incoming = new ConcurrentLinkedQueue<>();
    private final Channel[] channels = new Channel[16];
    private final Voice[] voices = new Voice[VOICES];
    private final float[] busLeft = new float[BLOCK], busRight = new float[BLOCK], reverbSend = new float[BLOCK],
            chorusSend = new float[BLOCK];
    private final Reverb reverb = new Reverb();
    private final Chorus chorus = new Chorus();
    private long blocks;
    /** Blocks since a voice last sounded: effects ring on for a while, then it is silent. */
    private int idleBlocks = Integer.MAX_VALUE / 2;
    private volatile boolean silent = true;

    public Synth(SoundFont font) {
        this.font = font;
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel(i == DRUM_CHANNEL);
        }
        for (int i = 0; i < voices.length; i++) {
            voices[i] = new Voice();
        }
    }

    /** A channel message as it is sent: its status byte (kind and channel) and two data bytes. */
    public static int message(int status, int channel, int data1, int data2) {
        return status | channel & 15 | (data1 & 127) << 8 | (data2 & 127) << 16;
    }

    /** Plays {@code message} (see {@link #message}) from the next render on. Any thread. */
    public void send(int message) {
        incoming.add(message);
    }

    /** Whether nothing sounds and nothing waits to: rendering it now gives silence. */
    public boolean silent() {
        return silent && incoming.isEmpty();
    }

    /** Renders the next {@code count} blocks into {@code left} and {@code right} from {@code offset}. One thread. */
    public void render(float[] left, float[] right, int offset, int count) {
        for (Integer message; (message = incoming.poll()) != null;) {
            handle(message);
        }
        for (int block = 0; block < count; block++, offset += BLOCK) {
            boolean sounding = false;
            for (Voice voice : voices) {
                if (voice.stage != DONE) {
                    voice.render();
                    sounding = true;
                }
            }
            idleBlocks = sounding ? 0 : idleBlocks + 1;
            // Effects ring on four seconds past the last voice.
            if (idleBlocks * SECONDS < 4) {
                chorus.process(chorusSend, busLeft, busRight, reverbSend);
                reverb.process(reverbSend, busLeft, busRight);
            }
            for (int i = 0; i < BLOCK; i++) {
                left[offset + i] = limit(busLeft[i] * MASTER);
                right[offset + i] = limit(busRight[i] * MASTER);
                busLeft[i] = busRight[i] = reverbSend[i] = chorusSend[i] = 0;
            }
            blocks++;
        }
        silent = idleBlocks * SECONDS >= 4;
    }

    /** Rounds off what goes past full scale instead of clipping it. */
    private static float limit(float x) {
        x = Math.clamp(x, -1.5f, 1.5f);
        return x - x * x * x / 6.75f;
    }

    private void handle(int message) {
        Channel channel = channels[message & 15];
        int data1 = message >> 8 & 127, data2 = message >> 16 & 127;
        switch (message & 0xF0) {
            case NOTE_ON -> {
                if (data2 == 0) {
                    noteOff(channel, data1);
                } else {
                    noteOn(channel, data1, data2);
                }
            }
            case NOTE_OFF -> noteOff(channel, data1);
            case CONTROL -> control(channel, data1, data2);
            case PROGRAM -> channel.program(data1);
            case PRESSURE -> channel.pressure = data1;
            case BEND -> channel.bend = data1 | data2 << 7;
            default -> {
            }
        }
        channel.version++;
    }

    private void noteOn(Channel channel, int key, int velocity) {
        for (Voice voice : voices) {
            if (voice.stage < LET_GO && voice.channel == channel && voice.key == key) {
                voice.keyDown = false;
                voice.release();
            }
        }
        int struck = channel.controllers[SOFT_PEDAL] >= 64 ? Math.max(1, velocity * 2 / 3) : velocity;
        for (Region region : channel.preset) {
            if (!region.plays(key, velocity)) {
                continue;
            }
            if (region.exclusiveClass != 0) {
                for (Voice voice : voices) {
                    if (voice.stage != DONE && voice.channel == channel && voice.region.exclusiveClass == region.exclusiveClass) {
                        voice.cut();
                    }
                }
            }
            free().start(channel, region, key, struck);
        }
    }

    /** A voice to play on: one that is done, else the quietest being let go, else the oldest. */
    private Voice free() {
        Voice chosen = voices[0];
        float worth = Float.MAX_VALUE;
        for (Voice voice : voices) {
            if (voice.stage == DONE) {
                return voice;
            }
            float value = (voice.stage == LET_GO ? -voice.attenuation : SILENT) + (voice.started - blocks) * 1e-4f;
            if (value < worth) {
                worth = value;
                chosen = voice;
            }
        }
        return chosen;
    }

    private void noteOff(Channel channel, int key) {
        for (Voice voice : voices) {
            if (voice.stage < LET_GO && voice.channel == channel && voice.key == key && voice.keyDown) {
                voice.keyDown = false;
                if (channel.controllers[SUSTAIN_PEDAL] < 64 && !voice.sostenuto) {
                    voice.release();
                }
            }
        }
    }

    private void control(Channel channel, int controller, int value) {
        channel.controllers[controller] = value;
        switch (controller) {
            case DATA -> {
                if (channel.controllers[101] == 0 && channel.controllers[100] == 0) {
                    channel.bendRange = value;
                }
            }
            case SUSTAIN_PEDAL -> letGo(channel, false);
            case SOSTENUTO_PEDAL -> {
                for (Voice voice : voices) {
                    if (voice.stage < LET_GO && voice.channel == channel) {
                        voice.sostenuto = value >= 64 && voice.keyDown;
                    }
                }
                letGo(channel, false);
            }
            case ALL_SOUND_OFF -> {
                for (Voice voice : voices) {
                    if (voice.stage != DONE && voice.channel == channel) {
                        voice.cut();
                    }
                }
            }
            case RESET_CONTROLLERS -> channel.resetControllers();
            case ALL_NOTES_OFF -> letGo(channel, true);
            default -> {
            }
        }
    }

    /** Lets go what no key or pedal of {@code channel} holds any more; with {@code all}, whatever holds it. */
    private void letGo(Channel channel, boolean all) {
        for (Voice voice : voices) {
            if (voice.stage < LET_GO && voice.channel == channel
                    && (all || !voice.keyDown && channel.controllers[SUSTAIN_PEDAL] < 64 && !voice.sostenuto)) {
                voice.keyDown = false;
                voice.release();
            }
        }
    }

    private static float gain(float decibels) {
        return (float) Math.pow(10, -decibels / 20);
    }

    private static float seconds(float timecents) {
        return timecents <= -12000 ? 0.001f : (float) Math.pow(2, Math.min(timecents, 8000) / 1200.0);
    }

    private static float hertz(float cents) {
        return (float) (8.176 * Math.pow(2, Math.clamp(cents, -16000, 4500) / 1200.0));
    }

    /** The standard's concave curve: 0 at 0, 1 at 1, rising steeply at the end. */
    private static float concave(float x) {
        return x >= 0.995f ? 1 : Math.clamp((float) (-40.0 / 96 * Math.log10(1 - x)), 0, 1);
    }

    private final class Channel {
        final boolean drums;
        final int[] controllers = new int[128];
        int program, pressure, bend = 8192;
        float bendRange = 2;
        /** Counted up with every message to it: voices work their modulators out again. */
        int version;
        Region[] preset;

        Channel(boolean drums) {
            this.drums = drums;
            controllers[VOLUME] = 100;
            controllers[PAN_POSITION] = 64;
            controllers[REVERB_SEND] = 40;
            controllers[RESONANCE_LEVEL] = controllers[RELEASE_TIME] = controllers[ATTACK_TIME] = controllers[BRIGHTNESS] = 64;
            resetControllers();
            program(0);
        }

        void resetControllers() {
            controllers[EXPRESSION] = controllers[100] = controllers[101] = 127;
            controllers[MODULATION] = controllers[SUSTAIN_PEDAL] = controllers[SOSTENUTO_PEDAL] = controllers[SOFT_PEDAL] = 0;
            pressure = 0;
            bend = 8192;
            bendRange = 2;
        }

        void program(int number) {
            program = number;
            preset = font.preset(drums ? DRUMS : controllers[BANK], number);
            font.prepare(preset);
        }
    }

    private final class Voice {
        int stage = DONE;
        Channel channel;
        Region region;
        short[] pcm;
        int key, sounded, velocity;
        long started;
        boolean keyDown, sostenuto, looping;
        /** Its generators with the modulators of its key and velocity, and with those of its channel's controllers too. */
        final float[] struck = new float[GENERATORS], g = new float[GENERATORS];
        /** The modulators its channel's controllers move, worked out again when one does. */
        final Modulator[] moving = new Modulator[24];
        int movingCount, seen;
        /** Where in the sample it is (frames), and where it ends and loops. */
        double position;
        int end, loopStart, loopEnd;
        float time, attack, hold, decay, sustain, release, attenuation, level, voiceGain;
        int modStage;
        float modTime, modLevel, modFrom, modSustain;
        float vibratoTime, vibratoPhase, lfoTime, lfoPhase;
        float left, right;
        float b0, b1, b2, a1, a2, z1, z2;

        void start(Channel channel, Region region, int key, int velocity) {
            this.channel = channel;
            this.region = region;
            this.key = key;
            sounded = region.fixedKey >= 0 ? region.fixedKey : key;
            this.velocity = region.fixedVelocity >= 0 ? region.fixedVelocity : velocity;
            pcm = font.pcm(region.sample);
            int frames = pcm.length - 2 * GUARD;
            end = region.end > 0 ? Math.min(region.end, frames) : frames + region.end;
            loopStart = Math.min(region.loopStart, end);
            loopEnd = Math.min(region.loopEnd, end);
            looping = region.loopMode != 0 && loopEnd - loopStart >= 8;

            System.arraycopy(region.generators, 0, struck, 0, GENERATORS);
            movingCount = 0;
            for (Modulator modulator : region.modulators) {
                if (moves(modulator.source()) || moves(modulator.amountSource())) {
                    if (movingCount < moving.length) {
                        moving[movingCount++] = modulator;
                    }
                } else {
                    struck[modulator.destination()] += value(modulator);
                }
            }
            seen = channel.version - 1;
            modulate();

            position = Math.max(0, g[START] + g[START_COARSE] * 32768);
            if (position >= end) {
                stage = DONE;
                return;
            }
            started = blocks;
            keyDown = true;
            sostenuto = false;
            float slower = Math.max(0, channel.controllers[ATTACK_TIME] - 64) / 63f;
            attack = seconds(g[ATTACK]) * (float) Math.pow(2, (channel.controllers[ATTACK_TIME] - 64) / 16.0) + slower * slower * 2;
            hold = seconds(g[HOLD] + g[KEY_TO_HOLD] * (60 - sounded));
            decay = seconds(g[DECAY] + g[KEY_TO_DECAY] * (60 - sounded));
            sustain = Math.clamp(g[SUSTAIN], 0, 1440) / 10;
            modSustain = 1 - Math.clamp(g[MOD_SUSTAIN], 0, 1000) / 1000;
            stage = modStage = WAITING;
            time = attenuation = level = modTime = modLevel = 0;
            vibratoTime = vibratoPhase = lfoTime = lfoPhase = 0;
            left = right = z1 = z2 = 0;
        }

        /** Whether the controller {@code source} names can move while a note sounds: not its key or velocity. */
        private static boolean moves(int source) {
            int index = source & 127;
            return (source & 128) != 0 || index == 10 || index == 13 || index == 14 || index == 16;
        }

        private float value(Modulator modulator) {
            return modulator.amount() * source(modulator.source())
                    * (modulator.amountSource() == 0 ? 1 : source(modulator.amountSource()));
        }

        /** The controller {@code source} names, as far along as it is: 0..1, or -1..1 for a two-way one, on its curve. */
        private float source(int source) {
            int index = source & 127;
            float x = (source & 128) != 0 ? channel.controllers[index] / 127f : switch (index) {
                case 2 -> velocity / 127f;
                case 3 -> sounded / 127f;
                case 13 -> channel.pressure / 127f;
                case 14 -> channel.bend / 16384f;
                case 16 -> channel.bendRange / 127f;
                default -> 0;
            };
            if ((source & 0x100) != 0) {
                x = 1 - x;
            }
            boolean bothWays = (source & 0x200) != 0;
            float along = bothWays ? Math.abs(2 * x - 1) : x;
            float curved = switch (source >> 10) {
                case 1 -> concave(along);
                case 2 -> 1 - concave(1 - along);
                case 3 -> bothWays ? 1 : x >= 0.5f ? 1 : 0;
                default -> along;
            };
            return bothWays && x < 0.5f ? -curved : curved;
        }

        /** Its generators as its channel's controllers have them now. */
        private void modulate() {
            if (seen == channel.version) {
                return;
            }
            seen = channel.version;
            System.arraycopy(struck, 0, g, 0, GENERATORS);
            for (int i = 0; i < movingCount; i++) {
                g[moving[i].destination()] += value(moving[i]);
            }
            voiceGain = gain(Math.max(0, g[ATTENUATION]) / 10) / 32768;
        }

        void release() {
            if (stage >= LET_GO) {
                return;
            }
            if (stage < FALLING) {
                attenuation = level > 0 ? Math.min(SILENT, (float) (-20 * Math.log10(level))) : SILENT;
            }
            float longer = Math.max(0, channel.controllers[RELEASE_TIME] - 64) / 63f;
            release = seconds(g[RELEASE]) * (float) Math.pow(2, (channel.controllers[RELEASE_TIME] - 64) / 16.0) + longer * longer * 3;
            stage = LET_GO;
            if (modStage < LET_GO) {
                modStage = LET_GO;
                modFrom = modLevel;
                modTime = 0;
            }
            // A sample looped only while its key is held plays out to its end.
            looping &= region.loopMode != 3;
        }

        /** Gone in a few milliseconds: cut off by its exclusive class, or to make room. */
        void cut() {
            release();
            release = 0.01f;
        }

        private void envelope() {
            switch (stage) {
                case WAITING -> {
                    time += SECONDS;
                    if (time >= seconds(g[DELAY])) {
                        stage = RISING;
                        time = 0;
                    }
                }
                case RISING -> {
                    time += SECONDS;
                    level = Math.min(1, time / attack);
                    if (time >= attack) {
                        stage = HELD;
                        time = 0;
                    }
                }
                case HELD -> {
                    time += SECONDS;
                    level = 1;
                    if (time >= hold) {
                        stage = FALLING;
                    }
                }
                case FALLING -> {
                    attenuation += 100 * SECONDS / decay;
                    if (attenuation >= sustain) {
                        attenuation = sustain;
                        stage = SUSTAINED;
                    }
                    level = gain(attenuation);
                }
                case LET_GO -> {
                    attenuation += 100 * SECONDS / release;
                    level = gain(attenuation);
                }
                default -> {
                }
            }
            if (attenuation >= SILENT) {
                stage = DONE;
            }
        }

        private void modulationEnvelope() {
            modTime += SECONDS;
            switch (modStage) {
                case WAITING -> {
                    if (modTime >= seconds(g[MOD_DELAY])) {
                        modStage = RISING;
                        modTime = 0;
                    }
                }
                case RISING -> {
                    float rise = seconds(g[MOD_ATTACK]);
                    modLevel = Math.min(1, modTime / rise);
                    if (modTime >= rise) {
                        modStage = HELD;
                        modTime = 0;
                    }
                }
                case HELD -> {
                    if (modTime >= seconds(g[MOD_HOLD] + g[31] * (60 - sounded))) {
                        modStage = FALLING;
                    }
                }
                case FALLING -> {
                    modLevel -= SECONDS / seconds(g[MOD_DECAY] + g[32] * (60 - sounded));
                    if (modLevel <= modSustain) {
                        modLevel = modSustain;
                        modStage = SUSTAINED;
                    }
                }
                case LET_GO -> modLevel = Math.max(0, modFrom - modTime / seconds(g[MOD_RELEASE]));
                default -> {
                }
            }
        }

        /** A triangle wave, -1..1, at {@code phase} cycles. */
        private static float triangle(float phase) {
            float cycle = phase - (float) Math.floor(phase);
            return cycle < 0.25f ? cycle * 4 : cycle < 0.75f ? 2 - cycle * 4 : cycle * 4 - 4;
        }

        void render() {
            Channel c = channel;
            modulate();
            envelope();
            if (stage == DONE) {
                return;
            }
            if (g[MOD_TO_PITCH] != 0 || g[MOD_TO_FILTER] != 0) {
                modulationEnvelope();
            }
            float vibrato = 0, lfo = 0;
            if (g[VIBRATO_TO_PITCH] != 0 && (vibratoTime += SECONDS) >= seconds(g[VIBRATO_DELAY])) {
                vibrato = triangle(vibratoPhase += hertz(g[VIBRATO_RATE]) * SECONDS);
            }
            if ((g[LFO_TO_PITCH] != 0 || g[LFO_TO_FILTER] != 0 || g[LFO_TO_VOLUME] != 0)
                    && (lfoTime += SECONDS) >= seconds(g[LFO_DELAY])) {
                lfo = triangle(lfoPhase += hertz(g[LFO_RATE]) * SECONDS);
            }
            float pitch = (sounded - region.rootKey) * g[SCALE_TUNING] + g[COARSE_TUNE] * 100 + g[FINE_TUNE] + region.correction
                    + vibrato * g[VIBRATO_TO_PITCH] + lfo * g[LFO_TO_PITCH] + modLevel * g[MOD_TO_PITCH];
            double step = region.sampleRate / RATE * Math.pow(2, pitch / 1200);

            float cut = g[CUTOFF] + modLevel * g[MOD_TO_FILTER] + lfo * g[LFO_TO_FILTER] + (c.controllers[BRIGHTNESS] - 64) * 75;
            float resonance = Math.max(0, g[RESONANCE] / 10 + (c.controllers[RESONANCE_LEVEL] - 64) * 0.3f);
            boolean filtered = cut < 13500 || resonance > 0;
            if (filtered) {
                double w = 2 * Math.PI * Math.min(8.176 * Math.pow(2, Math.max(cut, 1500) / 1200), RATE * 0.45) / RATE;
                double cos = Math.cos(w), alpha = Math.sin(w) / (2 * Math.pow(10, (resonance - 3.01) / 20));
                double a0 = 1 + alpha;
                b1 = (float) ((1 - cos) / a0);
                b0 = b2 = b1 / 2;
                a1 = (float) (-2 * cos / a0);
                a2 = (float) ((1 - alpha) / a0);
            }

            float amplitude = level * voiceGain * (lfo == 0 ? 1 : gain(lfo * g[LFO_TO_VOLUME] / 10));
            double angle = (Math.clamp(g[PAN] / 500, -1, 1) + 1) * Math.PI / 4;
            float toLeft = amplitude * (float) Math.cos(angle), toRight = amplitude * (float) Math.sin(angle);
            float leftStep = (toLeft - left) / BLOCK, rightStep = (toRight - right) / BLOCK;
            // Twice what the bank says: its sends are meant for a louder reverb than this one.
            float reverbShare = Math.clamp(g[REVERB] / 500, 0, 1), chorusShare = Math.clamp(g[CHORUS] / 1000, 0, 1);
            int loop = loopEnd - loopStart;
            for (int i = 0; i < BLOCK; i++) {
                int whole = (int) position;
                float f = (float) (position - whole);
                int at = whole + GUARD;
                float p0 = pcm[at - 1], p1 = pcm[at], p2 = pcm[at + 1], p3 = pcm[at + 2];
                float sample = p1 + 0.5f * f * (p2 - p0 + f * (2 * p0 - 5 * p1 + 4 * p2 - p3 + f * (3 * (p1 - p2) + p3 - p0)));
                if (filtered) {
                    float out = b0 * sample + z1;
                    z1 = b1 * sample - a1 * out + z2;
                    z2 = b2 * sample - a2 * out;
                    sample = out;
                }
                left += leftStep;
                right += rightStep;
                float l = sample * left, r = sample * right;
                busLeft[i] += l;
                busRight[i] += r;
                reverbSend[i] += (l + r) * reverbShare;
                chorusSend[i] += (l + r) * chorusShare;
                position += step;
                if (looping) {
                    while (position >= loopEnd) {
                        position -= loop;
                    }
                } else if (position >= end) {
                    stage = DONE;
                    return;
                }
            }
        }
    }

    /** Freeverb: eight damped combs into four all-passes a side, the right side's a little longer than the left's. */
    private static final class Reverb {
        private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617}, ALL_PASSES = {556, 441, 341, 225};
        private static final float FEEDBACK = 0.84f, DAMP = 0.25f, WET = 0.9f;
        private final float[][] combs = new float[16][], allPasses = new float[8][];
        private final int[] combAt = new int[16], allPassAt = new int[8];
        private final float[] damped = new float[16];

        Reverb() {
            for (int i = 0; i < 16; i++) {
                combs[i] = new float[(COMBS[i % 8] + (i < 8 ? 0 : 23)) * RATE / 44100];
            }
            for (int i = 0; i < 8; i++) {
                allPasses[i] = new float[(ALL_PASSES[i % 4] + (i < 4 ? 0 : 23)) * RATE / 44100];
            }
        }

        void process(float[] send, float[] left, float[] right) {
            for (int s = 0; s < BLOCK; s++) {
                // The offset keeps the lines out of denormal numbers as they die away.
                float in = send[s] * 0.015f + 1e-18f;
                float l = 0, r = 0;
                for (int i = 0; i < 16; i++) {
                    float[] line = combs[i];
                    int at = combAt[i];
                    float out = line[at];
                    damped[i] = out * (1 - DAMP) + damped[i] * DAMP;
                    line[at] = in + damped[i] * FEEDBACK;
                    combAt[i] = at + 1 == line.length ? 0 : at + 1;
                    if (i < 8) {
                        l += out;
                    } else {
                        r += out;
                    }
                }
                for (int i = 0; i < 8; i++) {
                    float[] line = allPasses[i];
                    int at = allPassAt[i];
                    float kept = line[at], through = i < 4 ? l : r;
                    line[at] = through + kept * 0.5f;
                    allPassAt[i] = at + 1 == line.length ? 0 : at + 1;
                    if (i < 4) {
                        l = kept - through;
                    } else {
                        r = kept - through;
                    }
                }
                left[s] += l * WET;
                right[s] += r * WET;
            }
        }
    }

    /** One delay line read at two slowly swept taps, one a side; some of it goes on to the reverb. */
    private static final class Chorus {
        private final float[] line = new float[4096];
        private int at;
        private double phase;

        void process(float[] send, float[] left, float[] right, float[] reverbSend) {
            for (int s = 0; s < BLOCK; s++) {
                line[at] = send[s];
                double sweep = Math.sin(phase) * 0.004 * RATE;
                phase += 2 * Math.PI * 0.6 / RATE;
                float l = tap(0.016 * RATE + sweep) * 0.7f, r = tap(0.016 * RATE - sweep) * 0.7f;
                left[s] += l;
                right[s] += r;
                reverbSend[s] += (l + r) * 0.3f;
                at = at + 1 & 4095;
            }
            phase %= 2 * Math.PI;
        }

        private float tap(double behind) {
            int whole = (int) behind;
            float f = (float) (behind - whole);
            return line[at - whole & 4095] * (1 - f) + line[at - whole - 1 & 4095] * f;
        }
    }
}
