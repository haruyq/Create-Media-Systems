package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.shapes.Shapes;

class LightingOcclusionTest {
    @Test
    void aShapeFillsTheHalfBlockCornersItsBoxesHoldTheMiddleOf() {
        assertEquals((byte) 0xFF, LightingOcclusion.bits(Shapes.block()));
        assertEquals(0, LightingOcclusion.bits(Shapes.empty()));
        // Bit x + 2y + 4z: a bottom slab (or a seat) fills the four with y 0, a top slab the other four.
        assertEquals(0x33, LightingOcclusion.bits(Shapes.box(0, 0, 0, 1, 0.5, 1)));
        assertEquals((byte) 0xCC, LightingOcclusion.bits(Shapes.box(0, 0.5, 0, 1, 1, 1)));
        assertEquals((byte) 0xAA, LightingOcclusion.bits(Shapes.box(0.5, 0, 0, 1, 1, 1)));
        // Thinner than half a block's middle: a carpet or a pane lets the light by.
        assertEquals(0, LightingOcclusion.bits(Shapes.box(0, 0, 0, 1, 1 / 16.0, 1)));
        assertEquals(0, LightingOcclusion.bits(Shapes.box(7 / 16.0, 0, 0, 9 / 16.0, 1, 1)));
        // Several boxes: a stair, its bottom half and its back top quarter.
        assertEquals((byte) (0x33 | 0x40 | 0x80), LightingOcclusion.bits(Shapes.or(Shapes.box(0, 0, 0, 1, 0.5, 1),
                Shapes.box(0, 0.5, 0.5, 1, 1, 1))));
    }
}
