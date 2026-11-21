package org.haruyq.createmediasystems.items.instrument.client;

import java.util.ArrayDeque;
import java.util.List;
import org.haruyq.createmediasystems.items.instrument.GuitarAmp;
import org.haruyq.createmediasystems.items.instrument.GuitarFrame;
import org.haruyq.createmediasystems.items.instrument.GuitarPatch;
import org.haruyq.createmediasystems.items.instrument.InstrumentItem;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import net.minecraft.world.entity.player.Player;

final class GuitarSound {
    private final ElectricStrings strings = new ElectricStrings();
    private final ArrayDeque<short[]> line = new ArrayDeque<>();
    private final PedalRack rack = new PedalRack(List.of());
    private volatile List<GuitarPatch.Setting> pedals = List.of();
    private volatile GuitarAmp amp = GuitarAmp.DEFAULT;
    private volatile int lineVolume = 127;
    private int offset;
    private boolean primed;
    private double dc, bass, speaker, rolloff, cabinetDc, previousPreamp;
    private double gain = 6, trim = .72, hardMix;

    void update(InstrumentState state, int message, long now) {
        amp = state.amp();
        lineVolume = state.volume();
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

    void render(float[] signal, int frames) {
        strings.render(signal,frames);
        mixInput(signal,frames);
        rack.configure(pedals);
        GuitarAmp settings = amp;
        double amount = settings.drive() / 127.0;
        double targetGain = settings.distortion() ? 6 * Math.pow(8, amount) : 2.2 * Math.pow(5.5, amount);
        double targetTrim = (settings.distortion() ? .65 : .82) - .19 * amount;
        double targetHard = settings.distortion() ? 1 : 0;
        for (int i = 0; i < frames; i++) {
            double input = rack.next(signal[i]);
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
            signal[i] = (float) (trim * (rolloff - cabinetDc));
        }
    }

    private static double clipIntegral(double x) {
        double magnitude = Math.abs(x);
        return magnitude <= 1 ? x * x * .5 : magnitude - .5;
    }
}
