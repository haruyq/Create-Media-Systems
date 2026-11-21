package org.haruyq.createmediasystems.items.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.haruyq.createmediasystems.client.mesh.Mesh;
import org.joml.Vector3f;

final class GuitarStrings {
    private static final int SEGMENTS = 12;
    private static final float U = 968f / 1024, V = 472f / 512;
    private GuitarStrings() {}

    static float fretX(InstrumentModel.Rig rig, float fret) {
        return (float)(rig.scale() * Math.pow(2, -fret / 12));
    }

    /** Points run from the tuning post, across the nut and fret, to the moving saddle. */
    static Vector3f[] path(InstrumentModel.Rig rig, int index, InstrumentAnimation.Frame frame) {
        var string = rig.strings().get(index);
        var nut = InstrumentModel.point(string.nut());
        var bridge = InstrumentModel.point(rig.bridge_pivot());
        var saddle = InstrumentModel.point(string.saddle()).sub(bridge)
                .rotateY((float)Math.toRadians(frame.control(InstrumentAnimation.Control.TREMOLO) * 12)).add(bridge);
        float fret = frame.frets()[index];
        float pressure = frame.pressures()[index];
        boolean pressed = fret > 0 && pressure > .0001f;
        var stop = new Vector3f(nut);
        if (pressed) {
            float x = fretX(rig, fret);
            float position = (nut.x - x) / (nut.x - saddle.x);
            stop.lerp(saddle, position);
            stop.y += .0105f * frame.bends()[index] * pressure;
            float crown = .036f + (float)Math.sqrt(rig.radius()*rig.radius() - stop.y*stop.y) - rig.radius() + .0018f;
            stop.z += (crown + string.diameter()/2 - stop.z) * pressure;
        }
        var points = new Vector3f[SEGMENTS + (pressed ? 3 : 2)];
        points[0] = InstrumentModel.point(string.tuner());
        points[1] = nut;
        int start = 1;
        if (pressed) points[++start] = stop;
        for (int i = 1; i < SEGMENTS; i++) {
            float t = (float)i / SEGMENTS;
            float wave = (float)Math.sin(t * Math.PI) * frame.vibrations()[index];
            points[start + i] = new Vector3f(stop).lerp(saddle, t).add(0, wave, wave * .22f);
        }
        points[points.length - 1] = saddle;
        return points;
    }

    static void render(PoseStack pose, VertexConsumer out, int light, int overlay,
                       InstrumentModel.Rig rig, InstrumentAnimation.Frame frame) {
        for (int i = 0; i < InstrumentAnimation.STRINGS; i++) {
            var points = path(rig, i, frame);
            // Subpixel high strings stay legible at ordinary Minecraft camera distances.
            float radius = Math.max(.00029f, rig.strings().get(i).diameter()/2);
            for (int j = 1; j < points.length; j++) {
                segment(pose,out,light,overlay,points[j-1],points[j],radius);
            }
        }
    }

    private static void segment(PoseStack pose, VertexConsumer out, int light, int overlay,
                                Vector3f a, Vector3f b, float radius) {
        var axis = new Vector3f(b).sub(a).normalize();
        var u = axis.cross(0, 0, 1, new Vector3f()).normalize(radius);
        var v = axis.cross(u, new Vector3f());
        var crossSection = new Vector3f[]{u, v, new Vector3f(u).negate(), new Vector3f(v).negate()};
        for (int s = 0; s < 4; s++) {
            Vector3f p = crossSection[s], q = crossSection[(s+1)%4];
            var normal = new Vector3f(p).add(q).normalize();
            vertex(pose,out,light,overlay,a,p,normal);
            vertex(pose,out,light,overlay,a,q,normal);
            vertex(pose,out,light,overlay,b,q,normal);
            vertex(pose,out,light,overlay,b,p,normal);
        }
    }

    private static void vertex(PoseStack pose, VertexConsumer out, int light, int overlay,
                               Vector3f base, Vector3f offset, Vector3f n) {
        Mesh.vertex(pose,out,base.x+offset.x,base.y+offset.y,base.z+offset.z,U,V,0xFFFFFF,n.x,n.y,n.z,light,overlay);
    }
}
