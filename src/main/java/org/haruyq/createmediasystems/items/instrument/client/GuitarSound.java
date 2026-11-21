package org.haruyq.createmediasystems.items.instrument.client;

import java.util.ArrayDeque;
import java.util.List;
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
    private volatile int amp = 1, lineVolume = 127;
    private int offset;
    private boolean primed;
    private double dc, bass, speaker, rolloff, drive = 5, trim = .72;

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
        int mode = amp;
        double gain = mode == 0 ? 1.6 : mode == 1 ? 5.6 : 15;
        double master = mode == 0 ? .92 : mode == 1 ? .66 : .54;
        for (int i=0; i<frames; i++) {
            double input = rack.next(signal[i]);
            dc += .0080*(input-dc);
            double ac = input-dc;
            bass += .036*(ac-bass);
            drive += .0025*(gain-drive);
            trim += .0025*(master-trim);
            double pre = drive*(ac-.34*bass);
            // A soft-limited preamp followed by a two-pole guitar cabinet rolloff.
            double clip = pre/Math.sqrt(1+pre*pre);
            speaker += .48*(clip-speaker);
            rolloff += .44*(speaker-rolloff);
            signal[i] = (float)(trim*rolloff);
        }
    }
}
