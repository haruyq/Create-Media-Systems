package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class ClientStreamsMixTest {
    private static final Vec3 EAR = new Vec3(0, 64, 0);

    @Test
    void oneSpeakerIsHeardWhereItIs() {
        ClientStreams.Mix mix = new ClientStreams.Mix(EAR);
        assertNull(mix.at(), "nothing heard yet");
        mix.add(new Vec3(5, 64, 0), 0.5);
        assertEquals(0.5, mix.gain(), 1e-9);
        assertEquals(5, mix.at().x, 1e-9);
        assertEquals(0, mix.radius(), 1e-6, "a point");
    }

    @Test
    void powersSumAndTheLoudestPullTheDirection() {
        ClientStreams.Mix mix = new ClientStreams.Mix(EAR);
        mix.add(new Vec3(10, 64, 0), 0.3);
        mix.add(new Vec3(0, 64, 10), 0.4);
        mix.add(new Vec3(-99, 64, 0), 0);
        assertEquals(0.5, mix.gain(), 1e-9, "√(0.3² + 0.4²)");
        Vec3 to = mix.at().subtract(EAR);
        assertTrue(to.z > to.x, "towards the louder one");
        assertEquals(10, to.length(), 1e-9, "as far as they are");
        assertTrue(mix.radius() > 0, "two directions are heard wider than one");
    }

    @Test
    void aWallInFrontIsHeardOverItsWidth() {
        ClientStreams.Mix narrow = new ClientStreams.Mix(EAR);
        ClientStreams.Mix wide = new ClientStreams.Mix(EAR);
        for (int i = -2; i <= 2; i++) {
            narrow.add(new Vec3(i, 64, 10), 0.5);
            wide.add(new Vec3(4 * i, 64, 10), 0.5);
        }
        assertEquals(0, narrow.at().x, 1e-9, "from its middle");
        assertTrue(wide.spread() > narrow.spread());
        assertTrue(wide.spread() < Math.PI, "still in front");
    }

    @Test
    void speakersAllAroundAreHeardFromAllAround() {
        ClientStreams.Mix mix = new ClientStreams.Mix(EAR);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            mix.add(EAR.add(8 * Math.cos(angle), 0, 8 * Math.sin(angle)), 0.5);
        }
        assertEquals(2 * Math.PI, mix.spread(), 1e-6);
        assertTrue(mix.radius() >= mix.at().distanceTo(EAR), "inside its radius: all around");
    }

    @Test
    void manySpeakersPlayNoLouderThanTheEngineCan() {
        ClientStreams.Mix mix = new ClientStreams.Mix(EAR);
        for (int i = 0; i < 2000; i++) {
            mix.add(new Vec3(i, 64, 10), 0.5);
        }
        assertEquals(1, mix.gain());
    }
}
