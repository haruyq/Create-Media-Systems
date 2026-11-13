package org.haruyq.createmediasystems.blocks.display;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class DisplayShadowsTest {
    @Test
    void blockGridPacksEightCellsWithoutAliasingNegativeCoordinatesOrUnequalSides() {
        try (var grid = new DisplayShadows.Grid(new BlockPos(-3, -5, -7), new BlockPos(1, -3, -4))) {
            assertEquals(12, grid.cells.capacity());
            grid.block(-3, -5, -7);
            grid.block(1, -5, -7);
            grid.block(-3, -4, -7);
            grid.block(-3, -5, -6);
            grid.block(1, -3, -4);
            for (int i = 0; i < grid.cells.capacity(); i++) {
                assertEquals(i == 0 ? 21 : i == 2 ? 1 : i == 11 ? 16 : 0, grid.cells.get(i));
            }
        }
    }

    @Test
    void allEightOccupancyBitsSurviveAndPaddingStaysEmpty() {
        try (var grid = new DisplayShadows.Grid(BlockPos.ZERO, new BlockPos(2, 2, 2))) {
            for (int bit = 0; bit < 8; bit++) {
                grid.block(bit & 1, bit >> 1 & 1, bit >> 2 & 1);
            }
            assertEquals(255, grid.cells.get(0) & 255);
            for (int i = 1; i < grid.cells.capacity(); i++) {
                assertEquals(0, grid.cells.get(i));
            }
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
