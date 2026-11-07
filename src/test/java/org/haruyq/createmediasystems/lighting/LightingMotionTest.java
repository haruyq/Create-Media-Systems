package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LightingMotionTest {
    @Test
    void smoothingDoesNotDependOnFrameRate() {
        LightingMotion.Angles slow = follow(20);
        LightingMotion.Angles fast = follow(120);
        assertEquals(slow.pan(), fast.pan(), 0.0001);
        assertEquals(slow.tilt(), fast.tilt(), 0.0001);
    }

    @Test
    void crossesThePanSeamAlongTheShortArc() {
        LightingMotion motion = new LightingMotion();
        motion.update(0, 179, 20, false);
        LightingMotion.Angles angles = motion.update(0.1, -179, 20, false);
        assertTrue(angles.pan() > 179 && angles.pan() < 181);
    }

    @Test
    void holdsLastAimWhenTargetDisappears() {
        LightingMotion motion = new LightingMotion();
        LightingMotion.Angles before = motion.update(0, 70, 35, false);
        LightingMotion.Angles after = motion.update(2, -90, -45, true);
        assertEquals(before, after);
    }

    @Test
    void resetsAcrossAWorldClockRewind() {
        LightingMotion motion = new LightingMotion();
        motion.update(50, 80, 10, false);
        assertEquals(new LightingMotion.Angles(-20, 45), motion.update(1, -20, 45, false));
    }

    @Test
    void speedAndAudioChangesKeepTheCurrentPhaseAndEaseIntoTheNewVelocity() {
        LightingMotion motion = new LightingMotion();
        double before = motion.phase(5000, 1, 0.35f);
        assertEquals(before, motion.phase(5000, 4, 1.2f));
        double first = motion.phase(5000 + 1 / 120.0, 4, 1.2f);
        double next = motion.phase(5000 + 2 / 120.0, 4, 1.2f);
        assertTrue(first - before > 0 && first - before < 0.005);
        assertTrue(next - first > first - before && next - first < 4.8 / 120);
    }

    @Test
    void audioDrivenPhaseDoesNotDependOnFrameRate() {
        assertEquals(phase(20), phase(120), 0.000001);
    }

    @Test
    void allFixturesShareOnePhaseWithoutAdvancingItTwicePerFrame() {
        LightingMotion motion = new LightingMotion();
        motion.phase(50, 1, 1);
        double phase = motion.phase(50.01, 1, 1);
        assertEquals(phase, motion.phase(50.01, 1, 1));
        assertEquals(phase, motion.phase(1, 1, 1));
        assertEquals(phase + 0.01, motion.phase(1.01, 1, 1), 0.000001);
    }

    @Test
    void preservesSubTickMotionInLongRunningWorlds() {
        long ticks = 100_000_000;
        double before = LightingMotion.seconds(ticks, 0.25f);
        double after = LightingMotion.seconds(ticks, 0.5f);
        assertEquals(0.0125, after - before, 0.00000001);
        assertEquals(0.0125, LightingMotion.seconds(ticks + 1, 0) - LightingMotion.seconds(ticks, 0.75f), 0.00000001);
    }

    private double phase(int frames) {
        LightingMotion motion = new LightingMotion();
        double value = motion.phase(0, 1, 0.35f);
        for (int frame = 1; frame <= frames; frame++) {
            value = motion.phase(frame / (double) frames, 4, 1.2f);
        }
        return value;
    }

    private LightingMotion.Angles follow(int frames) {
        LightingMotion motion = new LightingMotion();
        LightingMotion.Angles result = motion.update(0, 0, 0, false);
        for (int frame = 1; frame <= frames; frame++) {
            result = motion.update(frame / (double) frames, 80, 45, false);
        }
        return result;
    }
}
