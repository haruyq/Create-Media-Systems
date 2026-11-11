package org.haruyq.createmediasystems.launcher.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.haruyq.createmediasystems.client.mesh.RecordedMesh;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;

class LauncherGeometryTest {
    @Test void allSixCuboidFacesSurviveBackfaceCulling() {
        RecordedMesh tiled = new RecordedMesh(), flat = new RecordedMesh();
        Mesh.box(new PoseStack(), tiled, 0, 0, 0, 2, 3, 4, Mesh.Tile.METAL, 0, 0);
        Mesh.box(new PoseStack(), flat, 0, 0, 0, 2, 3, 4, 0xFFFFFF, 0);
        tiled.assertOutward();
        flat.assertOutward();
    }

    @Test void everyDiscSectorHasTheCorrectFrontFace() {
        RecordedMesh mesh = new RecordedMesh();
        LauncherRenderer.disc(new PoseStack(), mesh, 0, 0, 0, 3.6f, 0, 0);
        mesh.assertOutward();
    }

    @Test void theDiscGlowIsAnOutwardFacingRimSeparatedFromTheOpaqueBlade() {
        RecordedMesh mesh = new RecordedMesh();
        LauncherRenderer.discGlowRing(new PoseStack(), mesh, 0, 0);
        mesh.assertOutward();
        for (var v : mesh.vertices) assertTrue(Math.hypot(v[0], v[2]) > 3.6, "The glow must not duplicate the coplanar disc surface");
    }

    /** Opened, the whole front assembly turns as one rigid piece: every vertex keeps its distance from every other. */
    @Test void everyOpticAndReceiverVertexFollowsTheSameReloadHinge() {
        RecordedMesh closed = new RecordedMesh(), open = new RecordedMesh();
        LauncherRenderer.frontAssembly(new PoseStack(), closed, closed, 0, 0, 0, 0, 0);
        LauncherRenderer.frontAssembly(new PoseStack(), open, open, 1, 0, 0, 0, 0);
        assertEquals(closed.vertices.size(), open.vertices.size());
        closed.assertOutward();
        open.assertOutward();
        int n = closed.vertices.size();
        for (int anchor : new int[]{0, n / 3, 2 * n / 3, n - 1}) {
            for (int i = 0; i < n; i++) {
                assertEquals(distance(closed.vertices.get(anchor), closed.vertices.get(i)),
                        distance(open.vertices.get(anchor), open.vertices.get(i)), 2e-3,
                        "No front part may use a different origin or animation");
            }
        }
        double moved = 0;
        for (int i = 0; i < n; i++) moved = Math.max(moved, distance(closed.vertices.get(i), open.vertices.get(i)));
        assertTrue(moved > 1, "Opening moves the assembly");
    }

    private static double distance(float[] a, float[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }
}
