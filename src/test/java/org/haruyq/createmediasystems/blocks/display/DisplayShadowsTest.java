package org.haruyq.createmediasystems.blocks.display;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class DisplayShadowsTest {
    @Test
    void blockGridKeepsAByteABlockWithoutAliasingNegativeCoordinatesOrUnequalSides() {
        long slab = DisplayShadows.cells(0, 0, 0, 1, 0.5, 1);
        try (var grid = new DisplayShadows.Grid(new BlockPos(-3, -5, -7), new BlockPos(1, -3, -4))) {
            assertEquals(5 * 3 * 4, grid.cells.capacity());
            grid.block(-3, -5, -7, -1L);
            grid.block(-2, -5, -7, slab);
            grid.block(-3, -4, -7, 1L);
            grid.block(-3, -5, -6, 0);
            grid.block(1, -3, -4, slab);
            for (int i = 0; i < grid.cells.capacity(); i++) {
                assertEquals(i == 0 ? 255 : i == 1 || i == 59 ? 1 : i == 5 ? 2 : 0, grid.cells.get(i) & 255);
            }
            assertEquals(slab, grid.shapes[1]);
            assertEquals(1L, grid.shapes[2]);
        }
    }

    @Test
    void aShapeIsTheQuarterBlockCellsItsBoxesHoldTheMiddlesOf() {
        // A bottom slab: the lower two of each column's four.
        assertEquals(0x00FF00FF00FF00FFL, DisplayShadows.cells(0, 0, 0, 1, 0.5, 1));
        // A fence post, 6 to 10 sixteenths: one column of cells, a little to one side.
        assertEquals(0x22220000L, DisplayShadows.cells(0.375, 0, 0.375, 0.625, 1, 0.625));
        // A carpet is too thin to stand in the light's way.
        assertEquals(0, DisplayShadows.cells(0, 0, 0, 1, 0.0625, 1));
    }

    @Test
    void shapesPastWhatABlocksByteCanNameStandAsWholeBlocks() {
        try (var grid = new DisplayShadows.Grid(BlockPos.ZERO, new BlockPos(255, 0, 0))) {
            for (int i = 0; i < 256; i++) {
                grid.block(i, 0, 0, i + 1);
            }
            assertEquals(254, grid.cells.get(253) & 255);
            assertEquals(255, grid.cells.get(254) & 255);
        }
    }

    @Test
    void shadowWorkIsBoundedAcrossDisplaysAndCanResumeOnTheNextFrame() {
        var budget = new DisplayShadows.Budget(100);
        assertEquals(6, budget.take(1, 6, 12));
        assertEquals(1, budget.take(1, 20, 24));
        assertEquals(0, budget.take(1, 20, 6));
        assertEquals(2, budget.take(1, 20, 2));
        assertEquals(4, budget.take(2, 20, 24));
    }
}
