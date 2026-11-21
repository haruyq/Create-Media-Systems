package org.haruyq.createmediasystems.items.instrument.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.haruyq.createmediasystems.items.instrument.AutoEffect;
import org.haruyq.createmediasystems.items.instrument.GuitarPatch;
import org.junit.jupiter.api.Test;

final class PedalRackTest {
    private static final int RATE = org.haruyq.createmediasystems.playback.Pcm.RATE;

    @Test
    void wahTracksPickStrengthAndCompressorReducesDynamicRange() {
        double soft = wahBandRatio(.009);
        double hard = wahBandRatio(.23);
        assertTrue(hard > soft * 1.65, "The wah didn't open for the louder input: " + soft + " to " + hard);
        assertTrue(compressorGain(.015) > 1.08, "Quiet notes didn't receive make-up gain");
        assertTrue(compressorGain(.48) < .72, "Loud notes weren't compressed");
    }

    @Test
    void tremoloCyclesAndChorusReadsDelayedCopies() {
        var tremolo = new PedalRack(List.of(pedal(AutoEffect.TREMOLO, 3)));
        assertEquals(.1, tremolo.next(.1), .001);
        for (int i = 1; i < RATE / 8.8; i++) tremolo.next(.1);
        assertTrue(tremolo.next(.1) < .014, "The amplitude didn't fall at half a cycle");
        for (int i = (int) (RATE / 8.8) + 1; i < RATE / 4.4; i++) tremolo.next(.1);
        assertEquals(.1, tremolo.next(.1), .001);

        var chorus = new PedalRack(List.of(pedal(AutoEffect.CHORUS, 3)));
        assertEquals(.25, chorus.next(.5), .001);
        double firstTap = 0;
        double secondTap = 0;
        for (int i = 1; i < (int)(RATE*.036); i++) {
            double sample = chorus.next(0);
            if (i < (int)(RATE*.019)) firstTap += sample;
            else secondTap += sample;
        }
        assertTrue(firstTap > .10 && secondTap > .06, "One of the short chorus delays was lost");
    }

    @Test
    void bypassChangesSmoothlyBackToTheDrySignal() {
        var enabled = pedal(AutoEffect.COMPRESSOR, 3);
        var rack = new PedalRack(List.of(enabled));
        double previous = 0;
        for (int i = 0; i < RATE / 2; i++) previous = rack.next(.48);
        rack.configure(List.of(new GuitarPatch.Setting(enabled.link(), 0)));
        assertEquals(previous, rack.next(.48), .001);
        for (int i = 0; i < RATE / 5; i++) rack.next(.48);
        assertEquals(.48, rack.next(.48));


    }

    private static double wahBandRatio(double amplitude) {
        var rack = new PedalRack(List.of(pedal(AutoEffect.WAH, 3)));
        double upper = 0;
        double lower = 0;
        for (int i = 0; i < RATE; i++) {
            double low = Math.sin(2 * Math.PI * 280 * i / RATE);
            double high = Math.sin(2 * Math.PI * 1930 * i / RATE);
            double output = rack.next(amplitude * (low + high));
            if (i >= RATE / 2) {
                upper += output * high;
                lower += output * low;
            }
        }
        return Math.abs(upper / lower);
    }

    private static double compressorGain(double amplitude) {
        var rack = new PedalRack(List.of(pedal(AutoEffect.COMPRESSOR, 3)));
        double inputEnergy = 0;
        double outputEnergy = 0;
        for (int i = 0; i < RATE / 2; i++) {
            double sample = amplitude * Math.sin(2 * Math.PI * 380 * i / RATE);
            double output = rack.next(sample);
            if (i >= RATE / 4) {
                inputEnergy += sample * sample;
                outputEnergy += output * output;
            }
        }
        return Math.sqrt(outputEnergy / inputEnergy);
    }

    private static GuitarPatch.Setting pedal(AutoEffect effect, int strength) {
        return new GuitarPatch.Setting(new GuitarPatch.Link(GlobalPos.of(Level.OVERWORLD, new BlockPos(effect.ordinal(), 64, 0)),
                effect), strength);
    }

}
