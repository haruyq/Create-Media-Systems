package org.haruyq.createmediasystems.blocks.piano.client;

import net.minecraft.core.BlockPos;
import org.haruyq.createmediasystems.blocks.piano.grand.PianoPackets;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPackets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyMotionTest {
    @Test void renderTargetsKeepTheirPositionWhenTheChunkCursorMoves() {
        var cursor = new BlockPos.MutableBlockPos(11, 68, 4);
        var pos = cursor.immutable();
        var grand = PianoPackets.Target.placed(cursor);
        var keyboard = KeyboardPackets.Target.placed(cursor);
        cursor.set(15, 68, 5);
        assertEquals(PianoPackets.Target.placed(pos), grand);
        assertEquals(KeyboardPackets.Target.placed(pos), keyboard);
    }

    @Test void aStrikeAndReleaseBeforeTheNextFrameStillTravel() {
        var motion = new KeyMotion(21, 88);
        long start = 1_000_000_000;
        motion.press(24, start);
        motion.release(24, start);
        assertEquals(.5f, motion.travel(24, start + 18_000_000), .001f);
        assertEquals(1f, motion.travel(24, start + 50_000_000), .001f);
        assertTrue(motion.moving(start + 75_000_000));
        assertEquals(0, motion.travel(25, start + 75_000_000));
        assertFalse(motion.moving(start + 150_000_000));
    }

    @Test void aHeldKeyStaysDownAndRepeatedNotesDoNotJumpUp() {
        var motion = new KeyMotion(21, 88);
        long start = 1_000_000_000;
        motion.press(61, start);
        motion.press(61, start + 200_000_000);
        assertEquals(1, motion.travel(61, start + 200_000_000));
        motion.release(61, start + 220_000_000);
        long next = start + 255_000_000;
        float before = motion.travel(61, next);
        motion.press(61, next);
        assertEquals(before, motion.travel(61, next), .001f);
        assertEquals(1, motion.travel(61, next + 100_000_000));
    }
}
