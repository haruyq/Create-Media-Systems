package org.haruyq.createmediasystems.blocks.stagecustom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.stagecustom.StageCustomLayout.Drum;
import org.junit.jupiter.api.Test;

final class StageCustomLayoutTest {
    @Test
    void mountingAndArtworkUseTheFiveSbp2f5ShellSizes() {
        check(Drum.BASS, "SBB2217", 22, 17);
        check(Drum.TOM_10, "SBT1007", 10, 7);
        check(Drum.TOM_12, "SBT1208", 12, 8);
        check(Drum.FLOOR, "SBF1615", 16, 15);
        check(Drum.SNARE, "SBS1455", 14, 5.5);
    }

    @Test
    void eachMembraneCanBePickedWithTheSameTransformAsTheModel() {
        var root = new BlockPos(31, 64, -16);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Drum drum : Drum.values()) {
                var shell = StageCustomLayout.shell(drum);
                Vec3 aim = shell.point(shell.headRadius() * .30, -shell.headRadius() * .23, 0);
                Vec3 eye = aim.add(shell.normal().scale(.31));
                Vec3 from = StageCustomLayout.toWorld(root, facing, eye).subtract(Vec3.atBottomCenterOf(root));
                Vec3 target = StageCustomLayout.toWorld(root, facing, aim).subtract(Vec3.atBottomCenterOf(root));
                var hit = StageCustomLayout.aim(StageCustomLayout.toModel(facing, from),
                        StageCustomLayout.toModel(facing, target.subtract(from)).normalize(), 4);
                assertNotNull(hit, facing + " " + drum);
                assertEquals(drum, hit.drum());
                assertEquals(.31, hit.distance(), 1e-5);
                assertEquals(drum == Drum.BASS ? 0 : .30, hit.u(), 1e-5);
                assertEquals(drum == Drum.BASS ? 0 : -.23, hit.v(), 1e-5);
            }
        }
    }

    @Test
    void missesAndOutOfReachHitsDoNotPickAPad() {
        assertNull(StageCustomLayout.aim(new Vec3(2, 1, 0), new Vec3(0, -1, 0), 4));
        assertNull(StageCustomLayout.aim(new Vec3(.24, 10, .262), new Vec3(0, -1, 0), 4));
        var bass = StageCustomLayout.shell(Drum.BASS);
        Vec3 front = bass.point(0, 0, -bass.depthMetres() - .3);
        assertEquals(Drum.BASS, StageCustomLayout.aim(front, bass.normal(), 4).drum());
    }

    private static void check(Drum drum, String model, int diameter, double depth) {
        var shell = StageCustomLayout.shell(drum);
        assertEquals(model, shell.model());
        assertEquals(diameter * .0254, shell.diameterMetres(), 1e-8);
        assertEquals(depth * .0254, shell.depthMetres(), 1e-8);
    }
}
