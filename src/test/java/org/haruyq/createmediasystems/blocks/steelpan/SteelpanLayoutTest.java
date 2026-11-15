package org.haruyq.createmediasystems.blocks.steelpan;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SteelpanLayoutTest {
    @Test void authenticLowC29NoteLayoutUsesFourthAndFifthNeighbours() {
        var data = SteelpanLayout.DATA;
        assertEquals(.5842, data.diameter(), 1e-6);
        assertEquals(.2159, data.pan_depth(), 1e-6);
        assertEquals(12, data.notes().stream().filter(n -> n.ring()==0).count());
        assertEquals(12, data.notes().stream().filter(n -> n.ring()==1).count());
        assertEquals(5, data.notes().stream().filter(n -> n.ring()==2).count());
        for (int k=0;k<12;k++) {
            var note = SteelpanLayout.note(60+(7*k)%12);
            assertEquals(k*Math.PI/6,note.angle(),1e-6);
        }
        assertTrue(SteelpanLayout.note(60).center()[2]>.22, "Middle C is nearest the performer");
        assertTrue(SteelpanLayout.note(66).center()[2]<-.22, "F sharp is across the pan");
        for (int midi=60;midi<=88;midi++) {
            var n=SteelpanLayout.note(midi);
            assertEquals(midi,n.midi());
            assertEquals(midi,SteelpanLayout.at(n.center()[0],n.center()[2]),n.label());
        }
        assertEquals(-1,SteelpanLayout.at(0,0));
        assertEquals(-1,SteelpanLayout.at(.4f,0));
    }

    @Test void everyFieldCanBeSelectedFromAboveInEveryPlacementDirection() {
        for (var facing : Direction.Plane.HORIZONTAL) for (var note : SteelpanLayout.DATA.notes()) {
            Vec3 target = new Vec3(note.center()[0],note.center()[1],note.center()[2])
                    .xRot((float)-Math.toRadians(SteelpanLayout.DATA.tilt()))
                    .add(0,SteelpanLayout.DATA.rim_height(),0)
                    .yRot((float)-Math.toRadians(facing.toYRot()));
            assertEquals(note.midi(), SteelpanBlock.rayNote(target.add(0,1,0),new Vec3(0,-1,0),facing),
                    note.label()+" / "+facing);
            assertEquals(-1,SteelpanBlock.rayNote(target.subtract(0,1,0),new Vec3(0,1,0),facing));
        }
        assertEquals(-1,SteelpanBlock.rayNote(new Vec3(2,2,0),new Vec3(0,-1,0),Direction.SOUTH));
    }
}
