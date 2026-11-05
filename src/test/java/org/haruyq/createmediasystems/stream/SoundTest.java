package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(1, speaker.directivity(MOUTH.add(10, 0, 0)), 1e-9);
        assertEquals((1 + Streams.Sound.BEHIND_GAIN) / 2, speaker.directivity(MOUTH.add(0, 0, 10)), 1e-9);
        assertEquals(Streams.Sound.BEHIND_GAIN, speaker.directivity(MOUTH.add(-10, 0, 0)), 1e-9);
    }

    @Test
    void soundWithoutFacingIsHeardAlikeAllRound() {
        Streams.Sound jukebox = new Streams.Sound(MOUTH, Optional.empty(), Streams.Channel.MIX, BlockPos.ZERO);
        assertEquals(1, jukebox.directivity(MOUTH.add(-10, 0, 0)), 1e-9);
    }
}
