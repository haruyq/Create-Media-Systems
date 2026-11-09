package org.haruyq.createmediasystems.p2ptest;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.haruyq.createmediasystems.p2p.Transport;

/** Video frames as payloads: an 8-byte sequence number, then a JPEG, at most {@link Transport#MAX_PAYLOAD} in all. */
final class Frames {
    static final int WIDTH = 640, HEIGHT = 360;
    /** Largest picture a received frame may be, checked before decoding it. */
    static final int MAX_WIDTH = 1920, MAX_HEIGHT = 1080;
    private static final float[] QUALITIES = {0.8f, 0.6f, 0.4f, 0.25f, 0.1f};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    static {
        // In memory only: no temporary files per frame.
        ImageIO.setUseCache(false);
    }

    record Frame(long sequence, BufferedImage image) {
    }

    private Frames() {
    }

    /** The test pattern's frame {@code sequence}: a moving bar, the number and the time, all changing every frame. */
    static BufferedImage pattern(long sequence) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.getHSBColor(sequence % 100 / 100f, 0.5f, 0.35f));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(Color.WHITE);
        g.fillRect((int) (sequence * 16 % WIDTH), 0, 16, HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 48));
        g.drawString("#" + sequence, 32, 96);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 32));
        g.drawString(LocalTime.now().format(TIME), 32, 150);
        g.drawString("CMS P2P TEST", 32, HEIGHT - 32);
        g.dispose();
        return image;
    }

    /** {@code screen} scaled to fit a frame, letterboxed. */
    static BufferedImage fit(BufferedImage screen) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        double scale = Math.min((double) WIDTH / screen.getWidth(), (double) HEIGHT / screen.getHeight());
        int w = (int) (screen.getWidth() * scale), h = (int) (screen.getHeight() * scale);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(screen, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
        g.dispose();
        return image;
    }

    /**
     * {@code image} as frame {@code sequence}, at the best quality within {@code target} bytes; else the smallest that
     * fits a payload, or null if none does.
     */
    static byte[] encode(BufferedImage image, long sequence, int target) throws IOException {
        byte[] smallest = null;
        for (float quality : QUALITIES) {
            smallest = encode(image, sequence, quality);
            if (smallest.length <= target) {
                return smallest;
            }
        }
        return smallest.length <= Transport.MAX_PAYLOAD ? smallest : null;
    }

    private static byte[] encode(BufferedImage image, long sequence, float quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        out.writeBytes(ByteBuffer.allocate(8).putLong(sequence).array());
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** The frame in a received {@code payload}; null if it is not one, or its picture is over the largest size. */
    static Frame decode(byte[] payload) {
        if (payload.length <= 8 || payload.length > Transport.MAX_PAYLOAD) {
            return null;
        }
        long sequence = ByteBuffer.wrap(payload).getLong();
        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(payload, 8, payload.length - 8))) {
            reader.setInput(in, true, true);
            int w = reader.getWidth(0), h = reader.getHeight(0);
            if (w < 1 || h < 1 || w > MAX_WIDTH || h > MAX_HEIGHT) {
                return null;
            }
            return new Frame(sequence, reader.read(0));
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            reader.dispose();
        }
    }
}
