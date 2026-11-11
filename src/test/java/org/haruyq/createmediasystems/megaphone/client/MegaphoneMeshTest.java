package org.haruyq.createmediasystems.megaphone.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.haruyq.createmediasystems.client.mesh.RecordedMesh;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;

class MegaphoneMeshTest {
    @Test
    void theHornIsAClosedShellFacingOutInsideAndOut() {
        RecordedMesh mesh = new RecordedMesh();
        MegaphoneMesh.horn(new PoseStack(), mesh, 0xEEEEEE, 0xFFFFFF, 0);
        mesh.assertOutward();
        Map<String, Integer> edges = new HashMap<>();
        for (int i = 0; i < mesh.vertices.size(); i += 4) {
            for (int j = 0; j < 4; j++) {
                String a = point(mesh.vertices.get(i + j)), b = point(mesh.vertices.get(i + (j + 1) % 4));
                if (a.equals(b)) continue; // A disk's centre is repeated to make a triangle of a quad.
                edges.merge(a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a, 1, Integer::sum);
            }
        }
        edges.forEach((edge, quads) -> assertEquals(2, quads, "Exposed edge between rubber rim and horn lining: " + edge));
    }

    private static String point(float[] p) {
        return Math.round(p[0] * 1e6) + "," + Math.round(p[1] * 1e6) + "," + Math.round(p[2] * 1e6);
    }
}
