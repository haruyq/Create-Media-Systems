package org.haruyq.createmediasystems.items.instrument.client;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.client.KeyboardAudio;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.items.instrument.GuitarAmp;
import org.haruyq.createmediasystems.items.instrument.GuitarFrame;
import org.haruyq.createmediasystems.items.instrument.GuitarPatch;
import org.haruyq.createmediasystems.items.instrument.InstrumentItem;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

final class GuitarSound {
    /** Both sides of the synth mixed, at the level the presets had on the electronic piano's synth. */
    static final float LEVEL = .5f * .63f;
    private final Supplier<SoundFont> bank;
    private final InstrumentVoices strings = new InstrumentVoices();
    private Synth synth;
    private float[] left = new float[0], right = new float[0];
    private final ArrayDeque<short[]> line = new ArrayDeque<>();
    private final PedalRack inputPedals = new PedalRack(List.of()), samplePedals = new PedalRack(List.of());
    private volatile List<GuitarPatch.Setting> pedals = List.of();
    private volatile GuitarAmp amp = GuitarAmp.DEFAULT;
    private volatile int lineVolume = 127, brightness = 78;
    private int offset;
    private boolean primed;
    private double dc, bass, speaker, rolloff, cabinetDc, previousPreamp;
    private double gain = 6, trim = .72, hardMix;
    private double sampleDrive, sampleShade, sampleLow, sampleVolume = 1;
    private float[] inputBuffer = new float[0];

    GuitarSound() { this(() -> KeyboardAudio.bank(Minecraft.getInstance())); }

    /** Plays the presets of {@code bank}, which is null while it loads. */
    GuitarSound(Supplier<SoundFont> bank) { this.bank = bank; }

    void update(InstrumentState state, int message, long now) {
        amp = state.amp();
        lineVolume = state.volume();
        brightness = state.brightness();
        strings.update(state, message, now);
    }

    void pedals(Player player) {
        var stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof InstrumentItem item && item.kind == Kind.STRATOCASTER)) stack = player.getOffhandItem();
        var links = GuitarPatch.get(stack);
        pedals = links.links().isEmpty() ? List.of() : links.settings(player);
    }

    synchronized void offer(short[] block, boolean local) {
        if (block.length == 0) { line.clear(); offset = 0; primed = false; return; }
        // Bound latency when rendering stalls. Never play a backlog of old picking after the game resumes.
        while (line.size() >= (local ? 4 : 8)) { line.removeFirst(); offset = 0; }
        line.add(block);
        if (local || line.size() >= 3) primed = true;
    }

    private synchronized void mixInput(float[] signal, int frames) {
        if (!primed) return;
        int at = 0;
        double gain = lineVolume/(127.0*32768);
        while (at < frames && !line.isEmpty()) {
            short[] block = line.getFirst();
            int take = Math.min(frames-at, GuitarFrame.SAMPLES-offset);
            for (int i = 0; i < take; i++) signal[at++] += (float)(block[offset++]*gain);
            if (offset == block.length) { line.removeFirst(); offset = 0; }
        }
        if (at < frames) primed = false;
    }

    /** Renders {@code frames}, whole synth blocks, into {@code signal}. The audio thread. */
    void render(float[] signal, int frames) {
        renderStrings(signal, frames);
        if (inputBuffer.length < frames) inputBuffer = new float[frames];
        else Arrays.fill(inputBuffer, 0, frames, 0);
        mixInput(inputBuffer, frames);
        var chain = pedals;
        inputPedals.configure(chain);
        samplePedals.configure(chain);
        GuitarAmp settings = amp;
        double amount = settings.drive() / 127.0;
        // Both centre MIDI values are displayed as 50% on the guitar.
        double targetDrive = (settings.drive() - Math.clamp(settings.drive(), 63, 64)) / 63.0;
        double targetShade = (78 - brightness) / 53.0;
        double targetVolume = lineVolume / 127.0;
        double targetGain = settings.distortion() ? 6 * Math.pow(8, amount) : 2.2 * Math.pow(5.5, amount);
        double targetTrim = (settings.distortion() ? .65 : .82) - .19 * amount;
        double targetHard = settings.distortion() ? 1 : 0;
        for (int i = 0; i < frames; i++) {
            double pickup = sampleTone(signal[i], settings.distortion(), targetDrive, targetShade, targetVolume);
            double input = inputAmp(inputPedals.next(inputBuffer[i]), targetGain, targetTrim, targetHard);
            signal[i] = (float) (samplePedals.next(pickup) + input);
        }
    }

    private void renderStrings(float[] signal, int frames) {
        if (synth == null) {
            var font = bank.get();
            if (font == null) {
                Arrays.fill(signal, 0, frames, 0);
                return;
            }
            synth = new Synth(font, 32, 16);
        }
        if (left.length < frames) {
            left = new float[frames];
            right = new float[frames];
        }
        for (int start = 0; start < frames; start += Synth.BLOCK) {
            strings.nextBlock(synth);
            synth.render(left, right, start, 1);
        }
        for (int i = 0; i < frames; i++) signal[i] = (left[i] + right[i]) * LEVEL;
    }

    private double inputAmp(double input, double targetGain, double targetTrim, double targetHard) {
        dc += .008 * (input - dc);
        double ac = input - dc;
        bass += .036 * (ac - bass);
        gain += .0025 * (targetGain - gain);
        trim += .0025 * (targetTrim - trim);
        hardMix += .0025 * (targetHard - hardMix);
        double preamp = gain * (ac - (.24 + .34 * hardMix) * bass);
        double midpoint = (preamp + previousPreamp) * .5;

        // Unequal clipping thresholds give overdrive its asymmetric, rounded attack.
        double diode = midpoint < 0 ? 1.25 : 1;
        double soft = midpoint / Math.sqrt(1 + diode * diode * midpoint * midpoint);
        // Average the hard clip across this sample to suppress aliasing at the distortion stage.
        double delta = preamp - previousPreamp;
        double hard = Math.abs(delta) < 1e-5 ? Math.clamp(midpoint, -1, 1)
                : (clipIntegral(preamp) - clipIntegral(previousPreamp)) / delta;
        previousPreamp = preamp;
        double clipped = soft + hardMix * (hard - soft);
        speaker += .48 * (clipped - speaker);
        rolloff += .44 * (speaker - rolloff);
        cabinetDc += .0012 * (rolloff - cabinetDc);
        return trim * (rolloff - cabinetDc);
    }

    private double sampleTone(double sample, boolean distortion, double targetDrive, double targetShade, double targetVolume) {
        // The presets already contain the recorded amp. At the centre detent they pass at unity.
        sampleDrive += .0025 * (targetDrive - sampleDrive);
        sampleShade += .0025 * (targetShade - sampleShade);
        sampleVolume += .005 * (targetVolume - sampleVolume);
        sampleLow += .16 * (sample - sampleLow);
        double less = Math.max(0, -sampleDrive);
        double shade = 1 - (1 - sampleShade) * (1 - .72 * less);
        sample += shade * (sampleLow - sample);
        sample *= 1 - .48 * less / (1 + 5 * Math.abs(sample));
        if (sampleDrive > 0) {
            double pushed = sample * (1 + 9 * sampleDrive);
            double saturated = distortion ? Math.clamp(pushed, -1, 1)
                    : pushed / Math.sqrt(1 + pushed * pushed);
            sample += sampleDrive * (saturated / (1 + 3 * sampleDrive) - sample);
        }
        return sample * sampleVolume;
    }

    private static double clipIntegral(double x) {
        double magnitude = Math.abs(x);
        return magnitude <= 1 ? x * x * .5 : magnitude - .5;
    }
}
