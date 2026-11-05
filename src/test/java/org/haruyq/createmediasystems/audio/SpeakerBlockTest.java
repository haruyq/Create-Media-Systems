package org.haruyq.createmediasystems.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class SpeakerBlockTest {
    @Test
    void mouthIsTheFrontFace() {
        assertEquals(new Vec3(1, 0.5, 0.5), SpeakerBlock.mouth(BlockPos.ZERO, Direction.EAST));
    }
}
