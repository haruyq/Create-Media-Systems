package org.haruyq.createmediasystems.client.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** The quads drawn into it, each vertex as x, y, z and its normal. */
public final class RecordedMesh implements VertexConsumer {
    public final List<float[]> vertices = new ArrayList<>();

    @Override public VertexConsumer addVertex(float x, float y, float z) { vertices.add(new float[]{x, y, z, 0, 0, 0}); return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) {
        float[] p = vertices.getLast(); p[3] = x; p[4] = y; p[5] = z; return this;
    }

    /** Every quad is wound to face its normal, so backface culling keeps it, and none is degenerate. */
    public void assertOutward() {
        assertTrue(!vertices.isEmpty() && vertices.size() % 4 == 0);
        for (int i = 0; i < vertices.size(); i += 4) {
            double area = 0;
            for (int[] triangle : new int[][]{{0, 1, 2}, {2, 3, 0}}) {
                float[] a = vertices.get(i + triangle[0]), b = vertices.get(i + triangle[1]), c = vertices.get(i + triangle[2]);
                float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
                double dot = (uy * vz - uz * vy) * a[3] + (uz * vx - ux * vz) * a[4] + (ux * vy - uy * vx) * a[5];
                assertTrue(dot >= -1e-6, "Inward triangle at quad " + i / 4);
                area += dot;
            }
            assertTrue(area > 1e-6, "Entire face is degenerate at quad " + i / 4);
        }
    }
}
