package org.haruyq.createmediasystems.items.keytar.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;
import org.haruyq.createmediasystems.items.instrument.WearableInstruments.Kind;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class KeytarWearTest {
    private static KeytarMesh.Rig rig() throws Exception {
        try (var data = KeytarWearTest.class.getResourceAsStream(
                "/assets/create_media_systems/models/item/ax_edge/ax_edge.mesh.json")) {
            assertNotNull(data);
            return KeytarMesh.bake(new Gson().fromJson(new InputStreamReader(data, StandardCharsets.UTF_8), KeytarMesh.Geometry.class));
        }
    }

    @Test void physicalKeysMatchMidiFromBassToTrebleAndSinkAtTheirOwnHinges() throws Exception {
        var keys = rig().keys();
        assertEquals(49, keys.length);
        float previous = Float.POSITIVE_INFINITY;
        for (int index = 0; index < keys.length; index++) {
            var key = keys[index];
            assertEquals(index, key.index());
            assertEquals(Layout.black(Kind.KEYTAR.first + index), key.accidental());
            assertTrue(key.front()[0] < previous, key.name());
            previous = key.front()[0];
            var pose = new PoseStack();
            KeytarMesh.move(pose, key, 1);
            var tip = pose.last().pose().transformPosition(new Vector3f(key.front()));
            assertEquals(key.travel(), key.front()[2] - tip.z, .00025f, key.name());
            var pivot = pose.last().pose().transformPosition(new Vector3f(key.pivot()));
            assertEquals(0, pivot.distance(new Vector3f(key.pivot())), .000001f);
        }
    }

    @Test void theRigFitsInFrontAndBehindWithTheNeckOnThePerformersLeft() throws Exception {
        var rig = rig();
        var front = KeytarWear.hang(rig, 1, 0);
        var back = KeytarWear.hang(rig, 0, 0);
        assertTrue(front.m32() < -.125f);
        assertTrue(back.m32() > .125f);
        assertTrue(front.transformPosition(rig.neckPin(), new Vector3f()).x
                > front.transformPosition(rig.tailPin(), new Vector3f()).x);
        assertTrue(front.transformDirection(new Vector3f(0, 0, 1)).z < -.9f);
        assertTrue(back.transformDirection(new Vector3f(0, 0, 1)).z > .9f);
        assertTrue(KeytarWear.hang(rig, .5f, 0).m30() < -.375f);
    }

    @Test void strapEndsAreOnTheSuppliedPinsInBothPoses() throws Exception {
        var rig = rig();
        for (boolean front : new boolean[] {false, true}) {
            var pose = KeytarWear.hang(rig, front ? 1 : 0, 0);
            var route = front ? rig.frontStrap() : rig.backStrap();
            assertEquals(0, pose.transformPosition(rig.tailPin(), new Vector3f()).distance(new Vector3f(route.getFirst())), .05f);
            assertEquals(0, pose.transformPosition(rig.neckPin(), new Vector3f()).distance(new Vector3f(route.getLast())), .05f);
        }
    }
}
