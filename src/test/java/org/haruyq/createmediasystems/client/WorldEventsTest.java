package org.haruyq.createmediasystems.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

class WorldEventsTest {
    @Test
    void aChangedBlockReachesTheFacesBesideItAndTheirSections() {
        List<int[]> ranges = new ArrayList<>();
        List<Long> sections = new ArrayList<>();
        WorldEvents.onBlocksChanged((x0, y0, z0, x1, y1, z1) -> ranges.add(new int[] {x0, y0, z0, x1, y1, z1}));
        WorldEvents.onSectionChanged((x, y, z) -> sections.add(SectionPos.asLong(x, y, z)));

        WorldEvents.blockChanged(new BlockPos(16, 5, 31));
        assertArrayEquals(new int[] {15, 4, 30, 17, 6, 32}, ranges.getLast());
        // x 15..17 spans sections 0 and 1, z 30..32 sections 1 and 2.
        assertEquals(List.of(SectionPos.asLong(0, 0, 1), SectionPos.asLong(0, 0, 2), SectionPos.asLong(1, 0, 1),
                SectionPos.asLong(1, 0, 2)), sections);

        sections.clear();
        WorldEvents.sectionsChanged(0, 0, 0);
        assertArrayEquals(new int[] {-16, -16, -16, 31, 31, 31}, ranges.getLast());
        assertEquals(27, sections.size());
    }
}
