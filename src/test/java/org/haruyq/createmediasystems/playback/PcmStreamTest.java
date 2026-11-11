package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.stream.Streams;
import org.junit.jupiter.api.Test;

class PcmStreamTest {
    /** A stream of {@code source}, already open; {@code resume} reconnects it after it breaks off. */
    private static StreamSound.PcmStream pcm(InputStream source, Feed.Resume resume,
                                             Consumer<String> onInterrupted) {
        AtomicBoolean first = new AtomicBoolean(true);
        return new StreamSound.PcmStream(Feed.read(java.util.UUID.randomUUID().toString(), 0,
                offset -> first.getAndSet(false) ? source : resume.from(offset), onInterrupted), null,
                Streams.Channel.MIX, null, false);
    }

    /** format=ogg: half a second of a 440 Hz tone (made as the API makes it) comes out as the same PCM. */
    @Test
    void oggIsDecodedToLittleEndianPcm() throws IOException {
        byte[] pcm;
        try (InputStream ogg = StreamSound.decodeOgg(getClass().getResourceAsStream("/tone.ogg"))) {
            pcm = ogg.readAllBytes();
        }
        assertEquals(48_000, pcm.length, 1_000);
        int peak = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            peak = Math.max(peak, Math.abs((short) (pcm[i] & 0xff | pcm[i + 1] << 8)));
        }
        assertEquals(4173, peak, 300); // ffmpeg's own decode; swapped bytes would read as noise near full scale
        assertEquals(0, StreamSound.decodeOgg(InputStream.nullInputStream()).readAllBytes().length);
    }

    /** A stream the engine never reads (sound stopped while loading) must still let go of the download. */
    @Test
    void closingAnUnreadStreamClosesTheSource() throws Exception {
        CountDownLatch sourceClosed = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public void close() {
                sourceClosed.countDown();
            }
        };
        StreamSound.PcmStream stream = pcm(endless, offset -> {
            throw new IOException("no resume");
        }, reason -> interrupted.countDown());
        Thread.sleep(200); // let the pump fill the pipe and block
        stream.close();
        assertTrue(sourceClosed.await(5, TimeUnit.SECONDS), "pump kept the source open after close()");
        assertFalse(interrupted.await(200, TimeUnit.MILLISECONDS), "our own close was reported as an error");
    }

    @Test
    void aSourceErrorMidStreamIsReported() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        InputStream breaksOff = new InputStream() {
            private int left = 1000;

            @Override
            public int read() throws IOException {
                if (left-- <= 0) {
                    throw new IOException("connection reset");
                }
                return 0;
            }
        };
        pcm(breaksOff, offset -> {
            throw new IOException("API down");
        }, reason -> interrupted.countDown());
        assertTrue(interrupted.await(10, TimeUnit.SECONDS), "a stream that can't be resumed was not reported");
    }

    /** What a proxy cutting a long response looks like: the rest must arrive by reconnecting, unreported. */
    @Test
    void aBrokenOffStreamResumesWhereItStopped() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        double[] resumedAt = new double[1];
        InputStream firstPart = new InputStream() {
            private int left = 96_001; // a second of audio and half a sample

            @Override
            public int read() throws IOException {
                if (left-- <= 0) {
                    throw new IOException("Received RST_STREAM: Protocol error");
                }
                return 1;
            }
        };
        byte[] rest = new byte[96_000];
        java.util.Arrays.fill(rest, (byte) 2);
        StreamSound.PcmStream stream = pcm(firstPart, offset -> {
            resumedAt[0] = offset;
            return new java.io.ByteArrayInputStream(rest);
        }, reason -> interrupted.countDown());
        // While reconnecting the stream hands out silence (zeros) rather than waiting; count only the audio.
        int audio = 0;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            java.nio.ByteBuffer chunk = stream.read(192_002);
            if (!chunk.hasRemaining()) {
                break;
            }
            while (chunk.hasRemaining()) {
                audio += chunk.get() != 0 ? 1 : 0;
            }
            Thread.sleep(5);
        }
        assertEquals(96_001 + 96_000, audio, "audio after the break was lost");
        assertEquals(96_002 / 96_000.0, resumedAt[0], 1e-9, "resumed at the wrong position");
        assertFalse(interrupted.await(100, TimeUnit.MILLISECONDS), "a resumed stream was reported as broken");
    }

    /** Lined up 50 ms back: that much silence, then the audio from its start. */
    @Test
    void aNegativeLineUpPlaysSilenceFirst() throws Exception {
        byte[] audio = new byte[96_000];
        java.util.Arrays.fill(audio, (byte) 1);
        StreamSound.PcmStream stream = pcm(new java.io.ByteArrayInputStream(audio),
                offset -> InputStream.nullInputStream(), reason -> { });
        stream.prebuffer();
        stream.lineUp(-0.05, null);
        java.nio.ByteBuffer silence = stream.read(96_000);
        assertEquals(4_800, silence.remaining());
        while (silence.hasRemaining()) {
            assertEquals(0, silence.get());
        }
        assertEquals(1, stream.read(96_000).get(), "the audio did not follow from its start");
        stream.close();
    }

    /** A stalled download must never hold up the sound engine's thread, which the whole game waits on. */
    @Test
    void readingAStalledStreamReturnsSilenceAtOnce() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        InputStream stalls = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return -1;
            }

            @Override
            public void close() {
                never.countDown();
            }
        };
        StreamSound.PcmStream stream = pcm(stalls, offset -> InputStream.nullInputStream(),
                reason -> { });
        long started = System.nanoTime();
        java.nio.ByteBuffer chunk = stream.read(96_000);
        assertTrue(System.nanoTime() - started < 500_000_000L, "read waited for the download");
        assertTrue(chunk.hasRemaining(), "a stall was taken for the end of the track");
        stream.close();
        // The pump may not have the download open yet; it closes it as soon as it does.
        assertTrue(never.await(5, TimeUnit.SECONDS), "close() left the stalled download open");
    }
}
