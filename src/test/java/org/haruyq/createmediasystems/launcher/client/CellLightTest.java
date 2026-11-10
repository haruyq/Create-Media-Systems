package org.haruyq.createmediasystems.launcher.client;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.VertexConsumer;

class CellLightTest {
    @Test void skinReceivesOneColorAndLightAttributeAndKeepsItsAlphaAndSkyLight() {
        class Vertex implements VertexConsumer {
            int colors, lights, r, g, b, alpha, block, sky;
            public VertexConsumer addVertex(float x,float y,float z) { return this; }
            public VertexConsumer setColor(int r,int g,int b,int a) { colors++;this.r=r;this.g=g;this.b=b;alpha=a;return this; }
            public VertexConsumer setUv(float u,float v) { return this; }
            public VertexConsumer setUv1(int u,int v) { return this; }
            public VertexConsumer setUv2(int u,int v) { lights++;block=u;sky=v;return this; }
            public VertexConsumer setNormal(float x,float y,float z) { return this; }
        }
        Vertex target = new Vertex();
        var lit = new CellLight(new Matrix4f(), 0, 1, 0, 3, 0xFF70B4, 1).wrap(target);
        lit.addVertex(0, 0, 0).setColor(255, 255, 255, 143).setUv(0, 0).setUv1(0, 0).setUv2(0, 224).setNormal(0, 1, 0);
        assertEquals(1, target.colors); assertEquals(1, target.lights);
        assertEquals(143, target.alpha); assertEquals(224, target.sky);
        assertTrue(target.block > 0); assertTrue(target.r > target.g);
    }
}
