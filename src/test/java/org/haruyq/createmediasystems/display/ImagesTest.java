package org.haruyq.createmediasystems.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.platform.NativeImage;

class ImagesTest {
    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void keepsColoursAndTransparency() throws IOException {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000); // opaque red
        image.setRGB(1, 0, 0x00000000); // see-through
        try (NativeImage decoded = Images.decode(png(image))) {
            assertEquals(2, decoded.getWidth());
            assertEquals(0xFF0000FF, decoded.getPixelRGBA(0, 0)); // ABGR
            assertEquals(0, decoded.getPixelRGBA(1, 0) >>> 24);
        }
    }

    @Test
    void asksDiscordsMediaProxyForPng() {
        assertEquals("https://media.discordapp.net/attachments/1/2/a.png?ex=6a&hm=6e&=&quality=lossless&format=png",
                Images.readable(URI.create("https://media.discordapp.net/attachments/1/2/a.png?ex=6a&hm=6e&=&format=webp&quality=lossless")).toString());
        assertEquals("https://media.discordapp.net/a.webp?format=png",
                Images.readable(URI.create("https://media.discordapp.net/a.webp")).toString());
        assertEquals("https://cdn.discordapp.com/a.png?format=webp",
                Images.readable(URI.create("https://cdn.discordapp.com/a.png?format=webp")).toString());
    }

    @Test
    void refusesOversizedAndUnknownFiles() throws IOException {
        byte[] wide = png(new BufferedImage(8193, 1, BufferedImage.TYPE_INT_ARGB));
        assertThrows(IOException.class, () -> Images.decode(wide));
        byte[] many = png(new BufferedImage(4097, 4096, BufferedImage.TYPE_BYTE_BINARY));
        assertThrows(IOException.class, () -> Images.decode(many));
        assertThrows(IOException.class, () -> Images.decode("not an image".getBytes()));
    }
}
