package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class SoundPhysicsCompatTest {
    @Test
    void delayedSoundThreadKeepsOneRequestAtTheLatestPositionPerSpeaker() {
        SoundPhysicsCompat left = new SoundPhysicsCompat();
        SoundPhysicsCompat right = new SoundPhysicsCompat();
        assertTrue(left.request(at(Vec3.ZERO)));
        for (int i = 1; i <= 100; i++) {
            assertFalse(left.request(at(new Vec3(i, 0, 0))));
        }
        assertTrue(right.request(at(new Vec3(0, 0, 1))));
        assertEquals(at(new Vec3(100, 0, 0)), left.take());
        assertEquals(at(new Vec3(0, 0, 1)), right.take());
        assertTrue(left.request(at(new Vec3(101, 0, 0))));
        assertEquals(at(new Vec3(101, 0, 0)), left.take());
        assertNull(left.take());
    }

    private static SoundPhysicsCompat.Request at(Vec3 position) {
        return new SoundPhysicsCompat.Request(position, position);
    }
}
