package org.haruyq.createmediasystems.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class LightingControlTest {
    @Test
    void gapsBetweenPresetPadsAndBelowMonitorControlsDoNotActivateControls() {
        var pad = new LightingControls.Hit(null, LightingPackets.Control.PRESET, 1, new Vec3(1.42, 0.9, 0.35), false);
        assertSame(pad, LightingControls.within(pad));
        assertNull(LightingControls.within(new LightingControls.Hit(null, LightingPackets.Control.PRESET, 1,
                new Vec3(1.42, 0.9, 0.6), false)));
        assertNull(LightingControls.within(new LightingControls.Hit(null, LightingPackets.Control.PRESET_BANK, 0,
                new Vec3(1.29, 1, 1.35), false)));
        assertNull(LightingControls.within(new LightingControls.Hit(null, LightingPackets.Control.VIDEO, 0,
                new Vec3(2.5, 1.2, 1.5), true)));
        var save = new LightingControls.Hit(null, LightingPackets.Control.PRESET_SAVE, 0, new Vec3(1.68, 1, 1.35), false);
        assertSame(save, LightingControls.within(save));
    }

    @Test
    void malformedControlPacketsAreRejectedBeforeReadingConsoleState() {
        int rgb = LightingPackets.Control.RGB.ordinal();
        LightingPackets.Change[] malformed = {
                new LightingPackets.Change(BlockPos.ZERO, -1, rgb, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, LightingSettings.GROUPS, rgb, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, -1, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.values().length, 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, -1, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 64, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 0, Float.NaN, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 0, Float.POSITIVE_INFINITY, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, rgb, 0, 0, Float.NEGATIVE_INFINITY),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.PRESET.ordinal(), 6, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.PRESET.ordinal(), 0, 3, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.PRESET_BANK.ordinal(), 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.PRESET_SAVE.ordinal(), 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.RANGE_ALL.ordinal(), 0, 0, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.RANGE_ALL.ordinal(), 0, 2, 0),
                new LightingPackets.Change(BlockPos.ZERO, 0, LightingPackets.Control.RANGE_ALL.ordinal(), 0, -300, 0)
        };
        for (LightingPackets.Change change : malformed) {
            assertFalse(LightingPackets.apply(null, change, null));
        }
    }

    @Test
    void multiblockCoordinatesAndControlSurfacesAgreeForEveryFacing() {
        BlockPos origin = new BlockPos(19, -32, -47);
        assertEquals(origin.east(), LightingConsoleBlock.at(origin, Direction.SOUTH, 1, 0, 0));
        assertEquals(origin.west(), LightingConsoleBlock.at(origin, Direction.NORTH, 1, 0, 0));
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            for (int column = 0; column < 4; column++) {
                for (int back = 0; back < 2; back++) {
                    for (int height = 0; height < 2; height++) {
                        BlockPos part = LightingConsoleBlock.at(origin, facing, column, height, back);
                        Vec3 local = LightingConsoleBlock.toLocal(origin, facing, part.getCenter());
                        assertEquals(new Vec3(column + 0.5, height + 0.5, back + 0.5), local);
                    }
                }
            }
        }
    }
}
