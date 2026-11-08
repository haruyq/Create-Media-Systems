package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class SoundTest {
    private static final Vec3 MOUTH = new Vec3(1, 0.5, 0.5);

    @Test
    void directionalSoundIsLoudestInFrontQuietestBehind() {
        Streams.Sound speaker = new Streams.Sound(MOUTH, Optional.of(Direction.EAST), Streams.Channel.MIX, BlockPos.ZERO);
        double front = speaker.directivity(MOUTH.add(10, 0, 0));
        double side = speaker.directivity(MOUTH.add(0, 0, 10));
        double behind = speaker.directivity(MOUTH.add(-10, 0, 0));
        assertEquals(1, front, 1e-9);
        assertTrue(front > side && side > behind && behind > 0, "fades from the front round to the back");
    }

    @Test
    void soundWithoutFacingIsHeardAlikeAllRound() {
        Streams.Sound jukebox = new Streams.Sound(MOUTH, Optional.empty(), Streams.Channel.MIX, BlockPos.ZERO);
        assertEquals(1, jukebox.directivity(MOUTH.add(-10, 0, 0)), 1e-9);
    }
}
