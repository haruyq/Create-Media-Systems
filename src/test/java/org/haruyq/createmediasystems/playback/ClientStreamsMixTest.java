package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class ClientStreamsMixTest {
    @Test
    void speakersOfOneSideSoundAsOneFromThoseHeardMost() {
        ClientStreams.Mix mix = new ClientStreams.Mix();
        assertNull(mix.at(), "nothing heard yet");
        mix.add(new Vec3(0, 0, 0), 0.3);
        mix.add(new Vec3(10, 0, 0), 0.4);
        mix.add(new Vec3(99, 0, 0), 0);
        assertEquals(0.5, mix.gain(), 1e-9, "powers sum: √(0.3² + 0.4²)");
        // Weighted by power, 0.09 : 0.16; a silent speaker pulls nothing.
        assertEquals(6.4, mix.at().x, 1e-9);
    }

    @Test
    void manySpeakersPlayNoLouderThanTheEngineCan() {
        ClientStreams.Mix mix = new ClientStreams.Mix();
        for (int i = 0; i < 2000; i++) {
            mix.add(new Vec3(i, 0, 0), 0.5);
        }
        assertEquals(1, mix.gain());
    }
}
