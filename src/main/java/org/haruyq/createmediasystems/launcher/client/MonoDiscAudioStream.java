package org.haruyq.createmediasystems.launcher.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;

/**
 * Stereo-to-mono downmix for vanilla/data-pack jukebox tracks, so the *music*
 * is spatialized by OpenAL. Stereo OpenAL buffers cannot be positioned in 3D.
 * Converts PCM16 LE in a streaming manner and carries incomplete frames between
 * reads. URL-based CMS audio is already 48 kHz mono and never passes through here.
 */
public final class MonoDiscAudioStream implements AudioStream {
    private final AudioStream source;
    private final AudioFormat format;
    private final boolean stereo;
    private ByteBuffer incoming = ByteBuffer.allocate(0);
    private final byte[] carry = new byte[4];
    private int carryLength;

    public MonoDiscAudioStream(AudioStream source) {
        this.source = source;
        AudioFormat original = source.getFormat();
        if (original.getChannels() == 2 && original.getSampleSizeInBits() == 16
                && !original.isBigEndian() && AudioFormat.Encoding.PCM_SIGNED.equals(original.getEncoding())) {
            stereo = true;
            format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, original.getSampleRate(), 16,
                    1, 2, original.getSampleRate(), false);
        } else if (original.getChannels() == 1) {
            stereo = false;
            format = original;
        } else {
            throw new IllegalArgumentException("Unsupported disc audio format: " + original);
        }
    }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (!stereo) return source.read(size);
        int targetFrames = Math.max(1, size / 2);
        ByteBuffer out = ByteBuffer.allocateDirect(targetFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
        while (out.remaining() >= 2) {
            while (carryLength < 4) {
                if (!incoming.hasRemaining()) {
                    incoming = source.read(Math.max(4096, out.remaining() * 2));
                    if (!incoming.hasRemaining()) {
                        out.flip();
                        return out;
                    }
                }
                carry[carryLength++] = incoming.get();
            }
            short left = (short) ((carry[0] & 255) | (carry[1] << 8));
            short right = (short) ((carry[2] & 255) | (carry[3] << 8));
            out.putShort((short) (((int) left + (int) right) / 2));
            carryLength = 0;
        }
        out.flip();
        return out;
    }

    /** Seek a local record to the same musical position already playing for other listeners. */
    public void skipSeconds(double seconds) throws IOException {
        if (!Double.isFinite(seconds) || seconds <= 0) return;
        long samples = (long) (seconds * format.getSampleRate());
        while (samples > 0) {
            int requested = (int) Math.min(samples, 8192);
            ByteBuffer data = read(requested * 2);
            int got = data.remaining() / 2;
            if (got <= 0) return;
            samples -= got;
        }
    }

    @Override
    public void close() throws IOException { source.close(); }
}
