package org.haruyq.createmediasystems.blocks.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;

class NoteBlockLocksTest {
    @Test
    void locksSurviveBeingSavedWithTheirChunk() {
        Map<BlockPos, UUID> locks = Map.of(new BlockPos(-30_000_000, -64, 12), UUID.randomUUID(),
                new BlockPos(5, 319, -7), UUID.randomUUID());
        assertEquals(locks, NoteBlockLocks.CODEC.parse(NbtOps.INSTANCE,
                NoteBlockLocks.CODEC.encodeStart(NbtOps.INSTANCE, locks).getOrThrow()).getOrThrow());
    }
}
