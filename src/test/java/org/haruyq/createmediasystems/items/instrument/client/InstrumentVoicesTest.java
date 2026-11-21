package org.haruyq.createmediasystems.items.instrument.client;

import java.nio.ByteBuffer;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.SoundFont;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.synth.Synth;
import org.haruyq.createmediasystems.items.instrument.InstrumentState;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentVoicesTest {
    @Test void everyPlayablePitchSoundsEvenWhenTheReleaseArrivesBeforeTheFirstAudioBuffer() throws Exception {
        SoundFont bank;
        try (var in = getClass().getResourceAsStream("/assets/create_media_systems/keyboard/a88mkii/generaluser_gs.sf3")) {
            bank = SoundFont.read(ByteBuffer.wrap(in.readAllBytes()));
        }
        for (var kind : new Kind[]{Kind.FLUTE, Kind.TRUMPET}) {
            var state = new InstrumentState(kind);
            var voices = new InstrumentVoices();
            var left = new float[Synth.BLOCK];
            var right = new float[Synth.BLOCK];
            for (int pitch = kind.first; pitch <= kind.last(); pitch++) {
                // A fresh synth isolates each pitch from the release tail of the previous one.
                var synth = new Synth(bank);
                synth.send(Synth.message(Synth.PROGRAM, 0, state.program(), 0));
                for (int velocity : new int[]{96, 0}) {
                    int message = InstrumentState.note(pitch, velocity);
                    state.apply(message);
                    voices.update(state, message, 1_000_000_000L);
                }
                float peak = 0;
                for (int block = 0; block < 160; block++) {
                    voices.nextBlock(synth);
                    synth.render(left, right, 0, 1);
                    for (int f = 0; f < Synth.BLOCK; f++) peak = Math.max(peak, Math.abs(left[f] + right[f]));
                }
                assertTrue(peak > .0002f, kind + " concert MIDI " + pitch + " peak " + peak);
            }
        }
    }
}
