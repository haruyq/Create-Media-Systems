package org.haruyq.createmediasystems.p2ptest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import org.haruyq.createmediasystems.p2p.Transport;
import org.junit.jupiter.api.Test;

class FramesTest {
    @Test
    void sendsThePatternWithinItsTargetAndDecodesIt() throws Exception {
        byte[] best = Frames.encode(Frames.pattern(7), 7, Transport.MAX_PAYLOAD);
        byte[] small = Frames.encode(Frames.pattern(7), 7, 4_000);
        assertTrue(best.length <= Transport.MAX_PAYLOAD);
        assertTrue(small.length < best.length, "a smaller target lowers the quality");
        Frames.Frame frame = Frames.decode(small);
        assertEquals(7, frame.sequence());
        assertEquals(Frames.WIDTH, frame.image().getWidth());
        assertEquals(Frames.HEIGHT, frame.image().getHeight());
        assertNotEquals(pixels(Frames.decode(Frames.encode(Frames.pattern(8), 8, 8_000)).image()), pixels(frame.image()));
    }

    @Test
    void refusesWhatIsNotAFrameItCanShow() throws Exception {
        byte[] frame = Frames.encode(Frames.pattern(1), 1, Transport.MAX_PAYLOAD);
        assertNull(Frames.decode(new byte[8]));
        assertNull(Frames.decode(new byte[Transport.MAX_PAYLOAD + 1]));
        byte[] noise = new byte[4096];
        new Random(1).nextBytes(noise);
        assertNull(Frames.decode(noise));
        // Truncated: shown as far as it goes, or refused, never thrown.
        Frames.decode(Arrays.copyOf(frame, frame.length / 2));
        // Within a payload, but over the largest picture: refused before decoding it.
        assertNull(Frames.decode(Frames.encode(new BufferedImage(Frames.MAX_WIDTH + 1, 8, BufferedImage.TYPE_INT_RGB), 1, 1)));
    }

    private static int pixels(BufferedImage image) {
        return Arrays.hashCode(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }
}
