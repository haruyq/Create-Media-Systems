package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

class EmitterRangeTest {
    private static final Vec3 JUKEBOX = new Vec3(0.5, 0.5, 0.5);

    @Test
    void defaultRangeIsVanillaDiscReach() {
        EmitterRange settings = EmitterRange.DEFAULT;
        assertTrue(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(60, 0.5, 0.5), 0));
        assertFalse(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(70, 0.5, 0.5), 0));
        assertTrue(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(70, 0.5, 0.5), EmitterRange.DROP_MARGIN));
        assertEquals(1, settings.gain(BlockPos.ZERO, JUKEBOX, JUKEBOX), 1e-6);
        assertEquals(0.5, settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(32.5, 0.5, 0.5)), 1e-6);
        assertEquals(0, settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(100, 0.5, 0.5)), 1e-6);
    }

    @Test
    void areaBoundsHearingAndFalloffReachesItsFarthestCorner() {
        // Blocks 0..9 on x, 0..0 on y and z: the farthest corner is (10, 1, 1).
        EmitterRange settings = new EmitterRange(Optional.of(new BoundingBox(0, 0, 0, 9, 0, 0)), 0.5f);
        assertTrue(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(9.9, 0.5, 0.5), 0));
        assertFalse(settings.inRange(BlockPos.ZERO, JUKEBOX, new Vec3(0.5, 3, 0.5), 0));
        assertEquals(0.5, settings.gain(BlockPos.ZERO, JUKEBOX, JUKEBOX), 1e-6, "full volume at the jukebox");
        float halfway = settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(5.5, 0.5, 0.5));
        float near = settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(9.5, 0.5, 0.5));
        assertTrue(0.5 > halfway && halfway > near && near > 0, "quieter further out, still heard at the edge");
        assertEquals(0, settings.gain(BlockPos.ZERO, JUKEBOX, new Vec3(10, 1, 1)), 1e-6, "silent at the farthest corner");
    }

    @Test
    void areaGoesWhereverTheJukeboxIs() {
        // Saved as one block east of the jukebox; the jukebox has since moved (onto a ship's plot, say).
        EmitterRange settings = new EmitterRange(Optional.of(new BoundingBox(1, 0, 0, 1, 0, 0)), 1);
        BlockPos moved = new BlockPos(1000, 64, -2000);
        Vec3 center = moved.getCenter();
        assertTrue(settings.inRange(moved, center, center.add(1, 0, 0), 0));
        assertFalse(settings.inRange(moved, center, new Vec3(1.5, 0.5, 0.5), 0));
    }

    @Test
    void volumeIsClamped() {
        assertEquals(1, new EmitterRange(Optional.empty(), 3).volume());
        assertEquals(0, new EmitterRange(Optional.empty(), -1).volume());
    }
}
