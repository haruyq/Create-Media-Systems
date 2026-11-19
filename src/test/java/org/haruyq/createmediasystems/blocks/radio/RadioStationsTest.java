package org.haruyq.createmediasystems.blocks.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class RadioStationsTest {
    @Test
    void aStationIsReceivedInFullUntilTheEdgeOfItsReachWhereItFadesOut() {
        var station = new RadioStations.Station(800, BlockPos.ZERO, 100, null);
        assertEquals(1, station.signal(Vec3.ZERO, new Vec3(0, 0, 0)));
        assertEquals(1, station.signal(Vec3.ZERO, new Vec3(85, 0, 0)));
        assertEquals(.5f, station.signal(Vec3.ZERO, new Vec3(0, 92.5, 0)), 1e-5);
        assertEquals(0, station.signal(Vec3.ZERO, new Vec3(0, 0, 100)));
        assertEquals(0, station.signal(Vec3.ZERO, new Vec3(300, 0, 0)));
    }
}
