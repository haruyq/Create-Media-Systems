package org.haruyq.createmediasystems.client.mesh;

import static org.haruyq.createmediasystems.client.mesh.Mesh.quad;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Shapes that instruments are built from, over {@link Mesh}'s quads. */
public final class Shapes {
    /** A colour that may change along a shape. */
    @FunctionalInterface
    public interface Tone {
        int at(float x);
    }

    private Shapes() {}

    /**
     * A body cut to an outline and chamfered round its edge. The outline is given at stations along x by its upper and
     * lower edge in y; the body lies between {@code front[i]} and {@code back[i]} in z, front being the lesser.
     */
    public static void slab(PoseStack p, VertexConsumer v, float[] x, float[] top, float[] bottom, float[] front,
            float[] back, float chamfer, Tone face, Tone edge, Tone rear, int light) {
        int last = x.length - 1;
        for (int i = 0; i < last; i++) {
            float xa = x[i], xb = x[i + 1], middle = (xa + xb) / 2;
            float ca = bevel(chamfer, top[i], bottom[i], front[i], back[i]);
            float cb = bevel(chamfer, top[i + 1], bottom[i + 1], front[i + 1], back[i + 1]);
            int faceTone = face.at(middle), edgeTone = edge.at(middle), rearTone = rear.at(middle);
            quad(p, v, xa, top[i] - ca, front[i], xb, top[i + 1] - cb, front[i + 1], xb, bottom[i + 1] + cb, front[i + 1],
                    xa, bottom[i] + ca, front[i], 0, 0, -1, faceTone, light);
            quad(p, v, xa, top[i] - ca, back[i], xb, top[i + 1] - cb, back[i + 1], xb, bottom[i + 1] + cb, back[i + 1],
                    xa, bottom[i] + ca, back[i], 0, 0, 1, rearTone, light);
            for (int side = -1; side <= 1; side += 2) {
                float[] rim = side > 0 ? top : bottom;
                float ya = rim[i], yb = rim[i + 1], run = xb - xa, rise = yb - ya;
                float length = (float) Math.sqrt(run * run + rise * rise);
                float nx = -side * rise / length, ny = side * run / length;
                quad(p, v, xa, ya - side * ca, front[i], xb, yb - side * cb, front[i + 1], xb, yb, front[i + 1] + cb,
                        xa, ya, front[i] + ca, nx * .7071f, ny * .7071f, -.7071f, edgeTone, light);
                quad(p, v, xa, ya, front[i] + ca, xb, yb, front[i + 1] + cb, xb, yb, back[i + 1] - cb,
                        xa, ya, back[i] - ca, nx, ny, 0, edgeTone, light);
                quad(p, v, xa, ya, back[i] - ca, xb, yb, back[i + 1] - cb, xb, yb - side * cb, back[i + 1],
                        xa, ya - side * ca, back[i], nx * .7071f, ny * .7071f, .7071f, edgeTone, light);
            }
        }
        for (int end = 0; end <= last; end += last) {
            if (top[end] - bottom[end] < 1e-4f) continue;
            quad(p, v, x[end], top[end], front[end], x[end], bottom[end], front[end], x[end], bottom[end], back[end],
                    x[end], top[end], back[end], end == 0 ? -1 : 1, 0, 0, edge.at(x[end]), light);
        }
    }

    private static float bevel(float chamfer, float top, float bottom, float front, float back) {
        return Math.max(0, Math.min(chamfer, Math.min((top - bottom) * .45f, (back - front) * .45f)));
    }

    /** A cylinder standing on the plane z = 0 and rising to z = {@code height}, capped at the far end. */
    public static void cylinder(PoseStack p, VertexConsumer v, float radius, float height, int segments, int side,
            int cap, int light) {
        for (int i = 0; i < segments; i++) {
            double a = i * 2 * Math.PI / segments, b = (i + 1) * 2 * Math.PI / segments, m = (a + b) / 2;
            float ca = (float) Math.cos(a) * radius, sa = (float) Math.sin(a) * radius;
            float cb = (float) Math.cos(b) * radius, sb = (float) Math.sin(b) * radius;
            quad(p, v, ca, sa, 0, cb, sb, 0, cb, sb, height, ca, sa, height, (float) Math.cos(m), (float) Math.sin(m), 0,
                    side, light);
        }
        Mesh.ring(p, v, 0, 0, height, radius, 0, segments, height < 0 ? -1 : 1, cap, light);
    }

    /**
     * A flat band following {@code path} (x, y, z triples), {@code half} wide to each side, lying against whatever
     * {@code out} (a unit vector per point) faces away from. Both of its faces show.
     */
    public static void ribbon(PoseStack p, VertexConsumer v, float[] path, float[] out, float half, int color, int light) {
        int points = path.length / 3;
        float[] edge = new float[path.length];
        for (int i = 0; i < points; i++) {
            int before = Math.max(i - 1, 0) * 3, after = Math.min(i + 1, points - 1) * 3, at = i * 3;
            float tx = path[after] - path[before], ty = path[after + 1] - path[before + 1], tz = path[after + 2] - path[before + 2];
            float wx = ty * out[at + 2] - tz * out[at + 1], wy = tz * out[at] - tx * out[at + 2], wz = tx * out[at + 1] - ty * out[at];
            float length = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (length < 1e-6f) length = 1;
            edge[at] = wx / length * half;
            edge[at + 1] = wy / length * half;
            edge[at + 2] = wz / length * half;
        }
        for (int i = 0; i < points - 1; i++) {
            int a = i * 3, b = a + 3;
            quad(p, v, path[a] - edge[a], path[a + 1] - edge[a + 1], path[a + 2] - edge[a + 2],
                    path[a] + edge[a], path[a + 1] + edge[a + 1], path[a + 2] + edge[a + 2],
                    path[b] + edge[b], path[b + 1] + edge[b + 1], path[b + 2] + edge[b + 2],
                    path[b] - edge[b], path[b + 1] - edge[b + 1], path[b + 2] - edge[b + 2],
                    out[a], out[a + 1], out[a + 2], color, light);
        }
    }
}
