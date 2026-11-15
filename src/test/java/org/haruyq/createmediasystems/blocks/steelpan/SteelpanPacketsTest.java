package org.haruyq.createmediasystems.blocks.steelpan;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SteelpanPacketsTest {
    @Test
    void aStrikeKeepsThePitchVelocityAndMalletAcrossTheNetwork() {
        var target = new SteelpanPackets.Target(282, new BlockPos(-4, 3, 15));
        var buffer = Unpooled.buffer();
        try {
            for (int note = SteelpanLayout.LOWEST; note <= SteelpanLayout.HIGHEST; note++) {
                for (int velocity : new int[] {1, 61, 127}) {
                    for (boolean left : new boolean[] {false, true}) {
                        var strike = new SteelpanPackets.Strike(target, note, velocity, left);
                        buffer.clear();
                        SteelpanPackets.Strike.CODEC.encode(buffer, strike);
                        assertEquals(strike, SteelpanPackets.Strike.CODEC.decode(buffer));
                        assertEquals(0, buffer.readableBytes());
                    }
                }
            }
        } finally {
            buffer.release();
        }
    }
}
