package org.haruyq.createmediasystems.blocks.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.haruyq.createmediasystems.blocks.train.TrainFeature;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

class SpeakerBlockTest {
    @Test
    void mouthIsTheFrontFace() {
        assertEquals(new Vec3(1, 0.5, 0.5), SpeakerBlock.mouth(BlockPos.ZERO, Direction.EAST));
    }

    /** Create's contraptions, schematics and structures turn and mirror blocks through these. */
    @Test
    void speakersTurnAndMirrorWithWhatTheyArePartOf() {
        for (Block speaker : new Block[] {AudioFeature.SPEAKER.get(), TrainFeature.TRAIN_SPEAKER.get()}) {
            BlockState north = speaker.defaultBlockState().setValue(SpeakerBlock.FACING, Direction.NORTH);
            assertEquals(Direction.EAST, north.rotate(Rotation.CLOCKWISE_90).getValue(SpeakerBlock.FACING));
            assertEquals(Direction.SOUTH, north.mirror(Mirror.LEFT_RIGHT).getValue(SpeakerBlock.FACING));
        }
    }
}
