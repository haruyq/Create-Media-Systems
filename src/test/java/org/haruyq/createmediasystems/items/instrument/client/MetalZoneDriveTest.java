package org.haruyq.createmediasystems.items.instrument.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.haruyq.createmediasystems.items.instrument.AutoEffect;
import org.haruyq.createmediasystems.items.instrument.GuitarPatch;
import org.haruyq.createmediasystems.items.instrument.MetalZoneBlock.Knob;
import org.haruyq.createmediasystems.items.instrument.MetalZoneSettings;
import org.haruyq.createmediasystems.playback.Pcm;
import org.junit.jupiter.api.Test;

final class MetalZoneDriveTest {
    private static final int RATE = Pcm.RATE;
    private static final MetalZoneSettings FLAT = MetalZoneSettings.DEFAULT;
    private static final GuitarPatch.Link LINK =
            new GuitarPatch.Link(GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), AutoEffect.METAL_ZONE);

    @Test
    void distortionAddsHarmonicsAndLevelCanMuteTheEffect() {
        var mild = FLAT.with(Knob.DIST, 0);
        var hard = FLAT.with(Knob.DIST, 100);
        double mildThird = response(mild, 220, .035, 3) / response(mild, 220, .035, 1);
        double hardThird = response(hard, 220, .035, 3) / response(hard, 220, .035, 1);
        assertTrue(hardThird > .15 && hardThird > mildThird * 4, "DIST did not increase nonlinear harmonics");
        assertEquals(0, response(hard.with(Knob.LEVEL, 0), 220, .15, 1));
    }

    @Test
    void middleBoostAndCutTrackTheIndependentFrequencyControl() {
        assertEquals(200, MetalZoneSettings.midHertz(0));
        assertEquals(5_000, MetalZoneSettings.midHertz(100));
        var boost = FLAT.with(Knob.MIDDLE, 100).with(Knob.MID_FREQ, 0);
        var cut = FLAT.with(Knob.MIDDLE, 0).with(Knob.MID_FREQ, 0);
        assertEquals(15, dbChange(boost, 200), .2);
        assertEquals(-15, dbChange(cut, 200), .2);
        assertTrue(Math.abs(dbChange(boost, 5_000)) < .5, "Low mids incorrectly boosted upper mids");
        var upper = FLAT.with(Knob.MIDDLE, 100).with(Knob.MID_FREQ, 100);
        assertEquals(15, dbChange(upper, 5_000), .2);
        assertTrue(Math.abs(dbChange(upper, 200)) < .5, "Upper mids incorrectly boosted low mids");
        assertEquals(0, dbChange(FLAT.with(Knob.MID_FREQ, 0), 200), 1e-5, "Flat mids should make the frequency knob inaudible");
    }

    @Test
    void lowAndHighShelvesApplyIndependently() {
        var low = FLAT.with(Knob.LOW, 100);
        assertTrue(dbChange(low, 45) > 11 && Math.abs(dbChange(low, 6_000)) < .5);
        var high = FLAT.with(Knob.HIGH, 0);
        assertTrue(dbChange(high, 9_000) < -11 && Math.abs(dbChange(high, 200)) < .5);
    }

    @Test
    void extremeSettingsRemainBoundedAndBypassIsDry() {
        var extreme = new MetalZoneSettings(100, 100, 100, 100, 100, 0);
        var rack = new PedalRack(List.of(new GuitarPatch.Setting(LINK, 1, extreme)));
        double peak = 0;
        for (int i = 0; i < RATE * 3; i++) {
            if (i % 8_000 == 0) {
                extreme = extreme.with(Knob.MID_FREQ, i % 16_000 == 0 ? 100 : 0);
                rack.configure(List.of(new GuitarPatch.Setting(LINK, 1, extreme)));
            }
            double signal = rack.next(.55 * Math.sin(i * .083) + .36 * Math.sin(i * .162));
            assertTrue(Double.isFinite(signal) && Math.abs(signal) < 1, "Non-finite or clipped output");
            peak = Math.max(peak, Math.abs(signal));
        }
        assertTrue(peak > .1);
        rack.configure(List.of(new GuitarPatch.Setting(LINK, 0, extreme)));
        for (int i = 0; i < RATE / 5; i++) rack.next(.21);
        assertEquals(.36, rack.next(.36));
    }

    private static double dbChange(MetalZoneSettings settings, int frequency) {
        return 20 * Math.log10(response(settings, frequency, .0008, 1) / response(FLAT, frequency, .0008, 1));
    }

    private static double response(MetalZoneSettings settings, int frequency, double amplitude, int harmonic) {
        var drive = new MetalZoneDrive(settings);
        double real = 0;
        double quadrature = 0;
        for (int i = 0; i < RATE; i++) {
            double phase = 2 * Math.PI * frequency * i / RATE;
            double sample = drive.next(amplitude * Math.sin(phase), 1);
            if (i >= RATE / 2) {
                real += sample * Math.cos(harmonic * phase);
                quadrature += sample * Math.sin(harmonic * phase);
            }
        }
        return Math.hypot(real, quadrature) / (RATE / 4);
    }
}
