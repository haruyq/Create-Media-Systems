package org.haruyq.createmediasystems.blocks.turntable.atlpa2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.haruyq.createmediasystems.blocks.component.m700.HifiLayout;
import org.haruyq.createmediasystems.blocks.turntable.atlpa2.TurntableLayout.Part;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class TurntableLayoutTest {
    /** Each part is found at its own middle, whichever way the turntable faces, with the cover as it shows it. */
    @Test
    void everyPartIsFoundAtItsMiddleForEveryFacing() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean open : new boolean[] {false, true}) {
                for (Part part : Part.values()) {
                    Vec3 middle = HifiLayout.toBlock(facing, (part.x0 + part.x1) / 2, (part.y0 + part.y1) / 2,
                            (part.z0 + part.z1) / 2);
                    Part found = TurntableLayout.partAt(facing, open, middle);
                    if (part.shown(open)) {
                        assertEquals(part, found, part + " facing " + facing + (open ? " open" : " shut"));
                        AABB bounds = TurntableLayout.shape(facing, open).bounds();
                        assertTrue(bounds.inflate(1e-6).contains(middle), part + " lies in the outline facing " + facing);
                    } else {
                        assertTrue(found != part, part + " is not there " + (open ? "open" : "shut"));
                    }
                }
            }
        }
    }

    /** The record lies under the shut cover: there is no reaching the platter or the arm through it. */
    @Test
    void theShutCoverCoversTheDeck() {
        Vec3 record = HifiLayout.toBlock(Direction.WEST, TurntableLayout.CENTER_X, TurntableLayout.RECORD_TOP,
                TurntableLayout.CENTER_Z);
        assertEquals(Part.COVER, TurntableLayout.partAt(Direction.WEST, false, record));
        assertEquals(Part.PLATTER, TurntableLayout.partAt(Direction.WEST, true, record));
        assertNull(TurntableLayout.partAt(Direction.WEST, true, new Vec3(.5, .9, .5)));
    }

    /** The arm swung for a groove puts its stylus on that groove, from the first one to the run-out. */
    @Test
    void theArmReachesEveryGroove() {
        for (double fraction = 0; fraction <= 1; fraction += .125) {
            double radius = TurntableLayout.radiusAt(fraction);
            assertEquals(fraction, TurntableLayout.fractionAt(radius), 1e-9);
            double[] stylus = TurntableLayout.stylus(TurntableLayout.armAngleFor(radius));
            assertEquals(radius, TurntableLayout.radius(stylus[0], stylus[1]), 1e-6, "groove at " + fraction);
        }
        double first = TurntableLayout.armAngleFor(TurntableLayout.LEAD_IN);
        double last = TurntableLayout.armAngleFor(TurntableLayout.RUN_OUT);
        assertTrue(first > 15 && first < 35 && last > first && last < 60, "swing " + first + " to " + last);
        // On its rest the stylus is clear of the record.
        double[] rest = TurntableLayout.stylus(0);
        assertTrue(TurntableLayout.radius(rest[0], rest[1]) > TurntableLayout.RECORD_RADIUS);
    }
}
