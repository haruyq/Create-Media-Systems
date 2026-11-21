package org.haruyq.createmediasystems.blocks.stagecustom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Cymbal;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Pedal;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomCymbals.Voice;
import org.junit.jupiter.api.Test;

final class StageCustomCymbalsTest {
    @Test
    void azmDimensionsAndRidePlayZonesMatchTheModel() {
        assertEquals(14, StageCustomCymbals.plate(Cymbal.HI_HAT).diameter());
        assertEquals(16, StageCustomCymbals.plate(Cymbal.CRASH).diameter());
        assertEquals(20, StageCustomCymbals.plate(Cymbal.RIDE).diameter());
        assertEquals(Voice.CRASH, strike(Cymbal.CRASH, .55, false).voice());
        assertEquals(Voice.RIDE_BOW, strike(Cymbal.RIDE, .69, false).voice());
        assertEquals(Voice.RIDE_BELL, strike(Cymbal.RIDE, .13, false).voice());
    }

    @Test
    void openingTheHatRaisesTheTopPlateAndChangesItsVoice() {
        var closed = strike(Cymbal.HI_HAT, .7, false);
        var open = strike(Cymbal.HI_HAT, .7, true);
        assertEquals(Voice.HAT_CLOSED, closed.voice());
        assertEquals(Voice.HAT_OPEN, open.voice());
        assertEquals(StageCustomCymbals.openGap(), closed.distance() - open.distance(), 1e-6);
    }

    @Test
    void theTwoPedalsCanBePickedSeparately() {
        for (Pedal pedal : Pedal.values()) {
            var center = StageCustomCymbals.hinge(pedal).add(0, .9, -.049);
            var hit = StageCustomCymbals.aimPedal(center, new Vec3(0, -1, 0), 2);
            assertNotNull(hit);
            assertEquals(pedal, hit.pedal());
        }
    }

    private static StageCustomCymbals.Hit strike(Cymbal cymbal, double radial, boolean open) {
        var disk = StageCustomCymbals.plate(cymbal);
        var spot = disk.point(disk.radius() * radial, 0, disk.profile(disk.radius() * radial));
        var hit = StageCustomCymbals.aim(spot.add(disk.normal().scale(.81)), disk.normal().scale(-1), 3, open);
        assertNotNull(hit, cymbal + " did not receive a strike");
        assertEquals(cymbal, hit.cymbal());
        return hit;
    }
}
