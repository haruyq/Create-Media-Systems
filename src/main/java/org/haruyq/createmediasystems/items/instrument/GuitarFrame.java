package org.haruyq.createmediasystems.items.instrument;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Twenty milliseconds of mono IMA ADPCM. Each packet carries its own decoder state and six fingerings. */
public final class GuitarFrame {
    public static final int STRINGS = 6, SAMPLES = 960, FINGERING = STRINGS * 3;
    public static final int BYTES = FINGERING + 3 + SAMPLES / 2;
    public static final StreamCodec<ByteBuf, byte[]> CODEC = ByteBufCodecs.byteArray(BYTES);

    private GuitarFrame() {}

    public static boolean valid(byte[] frame) {
        if (frame.length == 0) return true; // Stop, including when an input device is disconnected.
        if (frame.length != BYTES || (frame[FINGERING + 2] & 255) > 88) return false;
        for (int i = 0; i < FINGERING; i += 3) {
            if ((frame[i] & 127) > 22 || frame[i + 1] < 0 || Math.abs((int) frame[i + 2]) > 100) return false;
        }
        return true;
    }
}
