package org.haruyq.createmediasystems.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

class SourceLinksTest {
    @Test
    void movedSourcesKeepTheirIdentityAcrossSaves() {
        SourceLinks links = new SourceLinks();
        UUID source = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BlockPos plot = new BlockPos(30_000_000, 64, 30_000_000);
        links.put(source, BlockPos.ZERO);
        links.put(other, BlockPos.ZERO.above());
        links.put(source, plot);
        links = SourceLinks.load(links.save(new CompoundTag(), null), null);
        assertEquals(plot, links.position(source));
        assertEquals(BlockPos.ZERO.above(), links.position(other));
        links.put(source, plot);
        assertFalse(links.isDirty());
        links.put(source, new BlockPos(5, 70, 10));
        assertTrue(links.isDirty());
        assertEquals(new BlockPos(5, 70, 10), links.position(source));
    }
}
