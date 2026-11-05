package org.haruyq.createmediasystems;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class SableCompatTest {
    @Test
    void distancesWithoutSableIncludeAllThreeAxes() {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SableCompat.class.getClassLoader());
        try {
            assertEquals(13, SableCompat.distance(null, BlockPos.ZERO, new BlockPos(3, 4, 12)), 1e-9);
            assertEquals(129, SableCompat.distance(null, BlockPos.ZERO, new BlockPos(0, 129, 0)), 1e-9);
        } finally {
            Thread.currentThread().setContextClassLoader(before);
        }
    }
}
