package org.haruyq.createmediasystems.items.keytar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class KeytarWearTest {
    /** The shoulders in the torso's upright frame, and how far a hand is from one. */
    private static final Vector3f RIGHT = new Vector3f(5 / 16f, -2 / 16f, 0), LEFT = new Vector3f(-5 / 16f, -2 / 16f, 0);
    private static final float ARM = 10 / 16f;

    @Test void aPlayedKeytarPutsItsKeysAndNeckAtArmsLength() {
        var hung = KeytarWear.hang(1, 0);
        for (int note = 48; note <= 84; note++) {
            Vector3f key = hung.transformPosition(new Vector3f(KeytarMesh.keyX(note), KeytarMesh.KEY_FRONT + .034f, KeytarMesh.KEY_TOP - .020f));
            assertEquals(ARM, key.distance(RIGHT), .07f, "note " + note);
        }
        Vector3f grip = hung.transformPosition(new Vector3f(KeytarMesh.GRIP_X, KeytarMesh.GRIP_Y, KeytarMesh.GRIP_Z + .030f));
        assertEquals(ARM, grip.distance(LEFT), .07f);
        assertTrue(grip.x < LEFT.x, "the neck is out to the wearer's left");
    }

    @Test void aStowedKeytarLiesAcrossTheBackWithItsKeysOutward() {
        var hung = KeytarWear.hang(0, 0);
        assertTrue(hung.transformPosition(new Vector3f()).z > 2 / 16f, "behind the torso");
        assertTrue(hung.transformDirection(new Vector3f(0, 0, -1)).z > .99f, "keys face away from the back");
        Vector3f neck = hung.transformPosition(new Vector3f(-.8f, .045f, 0)), tail = hung.transformPosition(new Vector3f(.475f, 0, 0));
        assertTrue(neck.y < tail.y && neck.x > tail.x, "neck down by the right hip, tail up by the left shoulder");
    }

    @Test void everyKeyHasItsPlaceAlongTheKeyboard() {
        float before = KeytarMesh.KEY_LEFT;
        int naturals = 0;
        for (int note = KeytarMesh.FIRST_NOTE; note < KeytarMesh.FIRST_NOTE + KeytarMesh.KEYS; note++) {
            float x = KeytarMesh.keyX(note);
            assertTrue(x > before && x < KeytarMesh.KEY_RIGHT, "note " + note);
            before = x;
            if (!KeytarMesh.sharp(note)) naturals++;
        }
        assertEquals(29, naturals);
    }
}
