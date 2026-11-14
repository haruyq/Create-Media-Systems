package org.haruyq.createmediasystems.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import net.minecraft.client.model.geom.ModelPart;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WornTest {
    @Test void anArmReachesAlongTheLineToItsTarget() {
        ModelPart arm = new ModelPart(List.of(), Map.of());
        arm.setPos(-5, 2, 0);
        arm.xRot = .4f;
        arm.yRot = .2f;
        Vector3f target = new Vector3f(-.10f, .45f, -.40f), pivot = new Vector3f(-5 / 16f, 2 / 16f, 0);
        Worn.reach(arm, target, 1);
        // An arm hangs along +y from its pivot until the part's turn is applied, z then y then x.
        Vector3f along = new Quaternionf().rotationZYX(arm.zRot, arm.yRot, arm.xRot).transform(new Vector3f(0, 1, 0));
        assertEquals(0, along.distance(new Vector3f(target).sub(pivot).normalize()), 1e-4f);
    }

    @Test void halfTheWeightTurnsAnArmHalfWay() {
        ModelPart arm = new ModelPart(List.of(), Map.of());
        arm.setPos(5, 2, 0);
        Worn.reach(arm, new Vector3f(5 / 16f, 2 / 16f, -1), .5f);
        assertEquals(-Math.PI / 4, arm.xRot, 1e-4f);
        assertEquals(0, arm.zRot, 1e-4f);
    }

    @Test void aFrameStandsUprightWithTheFrontAtMinusZ() {
        ModelPart body = new ModelPart(List.of(), Map.of());
        body.setPos(0, 4, 0);
        Matrix4f frame = Worn.frame(body);
        // To the wearer's right and up is towards -x and -y of the model, whose y runs down.
        assertEquals(0, frame.transformPosition(new Vector3f(1, 1, -1)).distance(-1, 4 / 16f - 1, -1), 1e-5f);
    }

    @Test void placementsBlendByTheShortTurn() {
        Matrix4f from = new Matrix4f().translate(1, 0, 0).rotateY((float) Math.toRadians(170));
        Matrix4f to = new Matrix4f().translate(3, 0, 0).rotateY((float) Math.toRadians(-170)).scale(2);
        Matrix4f half = Worn.blend(from, to, .5f);
        assertEquals(0, half.transformPosition(new Vector3f()).distance(2, 0, 0), 1e-5f);
        assertEquals(0, half.transformDirection(new Vector3f(1, 0, 0)).distance(-1.5f, 0, 0), 1e-4f);
    }
}
