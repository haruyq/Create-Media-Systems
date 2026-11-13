package org.haruyq.createmediasystems.blocks.piano.pxs1100we.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardBlock;
import org.haruyq.createmediasystems.blocks.piano.pxs1100we.KeyboardPowerAdapter;
import org.haruyq.createmediasystems.client.mesh.Mesh;

final class KeyboardAdapterRenderer implements BlockEntityRenderer<KeyboardPowerAdapter.Supply> {
    private static final int SEGMENTS = 18, SIDES = 8;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    @Override
    public void render(KeyboardPowerAdapter.Supply adapter, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        var level = adapter.getLevel();
        if (level == null) return;
        var pos = adapter.getBlockPos();
        var direction = KeyboardPowerAdapter.output(adapter.getBlockState());
        var port = pos.relative(direction);
        var state = level.getBlockState(port);
        if (!KeyboardBlock.isAdapterPort(port, state, direction.getOpposite())) return;
        var out = buffers.getBuffer(Mesh.solid());
        var forward = Vec3.atLowerCornerOf(direction.getNormal());
        var jack = KeyboardBlock.dcJack(KeyboardBlock.root(port, state), state).subtract(Vec3.atLowerCornerOf(pos));
        var start = new Vec3(.5, .189, .5).add(forward.scale(.395));
        var end = jack.subtract(forward.scale(.115));
        var a = start.add(forward.scale(.17)).add(0, -.11, 0);
        var b = end.subtract(forward.scale(.29)).add(0, -.24, 0);

        var last = start;
        for (int i = 1; i <= SEGMENTS; i++) {
            double t = i / (double) SEGMENTS, s = 1 - t;
            var next = start.scale(s * s * s).add(a.scale(3 * s * s * t))
                    .add(b.scale(3 * s * t * t)).add(end.scale(t * t * t));
            tube(pose, out, last, next, .015, .015, 0x17191C, light);
            last = next;
        }
        tube(pose, out, end, jack.subtract(forward.scale(.079)), .022, .032, 0x24262B, light);
        tube(pose, out, jack.subtract(forward.scale(.082)), jack.subtract(forward.scale(.026)), .034, .034, 0x15171B, light);
        tube(pose, out, jack.subtract(forward.scale(.027)), jack, .024, .024, 0x9B9EA2, light);
    }

    private static void tube(PoseStack pose, VertexConsumer out, Vec3 start, Vec3 end, double radiusA, double radiusB,
            int color, int light) {
        var run = end.subtract(start).normalize();
        var across = run.cross(UP).normalize();
        var up = across.cross(run);
        for (int side = 0; side < SIDES; side++) {
            var u = rim(across, up, side);
            var v = rim(across, up, side + 1);
            var a = start.add(u.scale(radiusA));
            var b = start.add(v.scale(radiusA));
            var c = end.add(v.scale(radiusB));
            var d = end.add(u.scale(radiusB));
            var normal = u.add(v).normalize();
            Mesh.quad(pose, out, (float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z,
                    (float) c.x, (float) c.y, (float) c.z, (float) d.x, (float) d.y, (float) d.z,
                    (float) normal.x, (float) normal.y, (float) normal.z, color, light);
        }
    }

    private static Vec3 rim(Vec3 across, Vec3 up, int side) {
        double angle = side * Math.TAU / SIDES;
        return across.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
    }

    @Override public AABB getRenderBoundingBox(KeyboardPowerAdapter.Supply adapter) {
        return new AABB(adapter.getBlockPos()).inflate(1.4);
    }
}
