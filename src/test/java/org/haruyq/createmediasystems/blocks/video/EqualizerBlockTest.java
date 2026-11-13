package org.haruyq.createmediasystems.blocks.video;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EqualizerBlockTest {
    @Test
    void aSecondEqualizerSplitsEachBandAndTakesItsLowerHalf() {
        assertEquals(EqualizerBlock.Band.LOW, EqualizerBlock.Band.of(0, false));
        assertEquals(EqualizerBlock.Band.BASS, EqualizerBlock.Band.of(0, true));
        assertEquals(EqualizerBlock.Band.HI, EqualizerBlock.Band.of(EqualizerBlock.FADERS - 1, false));
        for (int fader = 0; fader < EqualizerBlock.FADERS; fader++) {
            EqualizerBlock.Band first = EqualizerBlock.Band.of(fader, false);
            EqualizerBlock.Band second = EqualizerBlock.Band.of(fader, true);
            assertEquals(first.low(true), second.low(false), 0.01);
            assertEquals(first.low(false), second.high(), 0.01);
        }
        assertEquals(20, EqualizerBlock.Band.BASS.low(false), 0.01);
        assertEquals(20_000, EqualizerBlock.Band.HI.high(), 0.5);
    }
}
