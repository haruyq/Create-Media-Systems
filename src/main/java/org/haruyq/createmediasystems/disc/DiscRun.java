package org.haruyq.createmediasystems.disc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * One FFmpeg pass over a DVD title, cut into the stream's segments as it goes (see DiscSource#run). Only as far ahead
 * as is asked for: the segment after the one taken waits to be handed over, and FFmpeg waits on its full output pipe
 * behind it, so a paused or slow playback never encodes the rest of the title.
 */
final class DiscRun implements AutoCloseable {
    private static final int TS = 188;
    private static final long SEGMENT_TICKS = DiscSource.SEGMENT_SECONDS * 90_000L;
    /** A frame's PTS this far before a boundary already starts the next segment: frames sit exactly on it. */
    private static final long BOUNDARY_TOLERANCE_TICKS = 1000;
    private static final byte[] END = new byte[0];

    final DiscSource.Quality quality;
    final String encoder;
    private final Process process;
    private final DiscSource.Access access;
    private final SynchronousQueue<byte[]> segments = new SynchronousQueue<>();
    private final Thread reader;
    private final StringBuilder errors = new StringBuilder();
    private volatile IOException failure;
    private int next;
    private boolean ended;

    DiscRun(List<String> command, DiscSource.Access access, DiscSource.Quality quality, String encoder, int first) throws IOException {
        this.access = access;
        this.quality = quality;
        this.encoder = encoder;
        next = first;
        try {
            process = DiscSource.start(command, access);
        } catch (IOException missing) {
            throw new IOException("FFmpeg/FFprobe could not start; set discFfmpegPath and discFfprobePath in cms-client.toml");
        }
        process.getOutputStream().close();
        Thread.ofVirtual().start(() -> {
            try (InputStream err = process.getErrorStream()) {
                String text = new String(err.readNBytes(8192), StandardCharsets.UTF_8);
                synchronized (errors) {
                    errors.append(text);
                }
                err.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ended) {
                // The process was stopped.
            }
        });
        reader = Thread.ofVirtual().start(() -> {
            try (InputStream out = process.getInputStream()) {
                split(out, DiscSource.MAX_SEGMENT_BYTES, segments::put);
            } catch (IOException e) {
                failure = e;
            } catch (InterruptedException closed) {
                return;
            }
            try {
                segments.put(END);
            } catch (InterruptedException closed) {
                // Closed before the end was taken.
            }
        });
    }

    /** Whether the next segment it hands over is {@code index}, at this quality and encoder. */
    boolean continues(int index, DiscSource.Quality quality, String encoder) {
        return !ended && next == index && this.quality.equals(quality) && this.encoder.equals(encoder);
    }

    /** The next segment, waiting up to 30 s for it to be encoded. */
    byte[] take() throws IOException {
        byte[] segment;
        try {
            segment = ended ? END : segments.poll(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Disc reading was cancelled");
        }
        if (segment == null) {
            throw new IOException("The disc reader timed out; check the drive, disc and encoder");
        }
        if (segment == END) {
            ended = true;
            if (failure != null) {
                throw failure;
            }
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("The disc reader did not stop at the end of the title");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Disc reading was cancelled");
            }
            String text;
            synchronized (errors) {
                text = errors.toString();
            }
            String failed = DiscSource.failure(process.exitValue(), text, access);
            throw new IOException(failed != null ? failed : "The disc title ended before this segment");
        }
        next++;
        return segment;
    }

    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        reader.interrupt();
    }

    @FunctionalInterface
    interface Sink {
        void accept(byte[] segment) throws InterruptedException;
    }

    /**
     * Cuts FFmpeg's MPEG-TS output into segments of {@link DiscSource#SEGMENT_SECONDS} from its first video frame,
     * each from the video frame on a boundary (an IDR frame, see DiscSource#command) and led by the stream's PAT and
     * PMT, so it plays on its own.
     */
    static void split(InputStream in, int limit, Sink sink) throws IOException, InterruptedException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(), current = new ByteArrayOutputStream();
        byte[] packet = new byte[TS];
        int pmt = -1;
        long first = -1;
        long boundary = 0;
        while (in.readNBytes(packet, 0, TS) == TS) {
            if (packet[0] != 0x47) {
                throw new IOException("The disc encoder produced an invalid MPEG-TS stream");
            }
            int pid = (packet[1] & 0x1f) << 8 | packet[2] & 255;
            long pts = videoPts(packet);
            if (first < 0) {
                if (pid == 0 && pmt < 0) {
                    pmt = pmtPid(packet);
                    header.write(packet);
                } else if (pid == pmt || pid == 0x11) {
                    header.write(packet);
                }
                if (pts >= 0) {
                    first = pts;
                    boundary = first + SEGMENT_TICKS;
                }
            } else if (pts >= 0 && pts >= boundary - BOUNDARY_TOLERANCE_TICKS) {
                sink.accept(current.toByteArray());
                current.reset();
                header.writeTo(current);
                while (pts >= boundary - BOUNDARY_TOLERANCE_TICKS) {
                    boundary += SEGMENT_TICKS;
                }
            }
            current.write(packet);
            if (current.size() > limit) {
                throw new IOException("The disc encoder exceeded its output limit");
            }
        }
        if (first >= 0) {
            sink.accept(current.toByteArray());
        }
    }

    /** The PTS of a video PES starting in this TS packet, or -1. */
    static long videoPts(byte[] packet) {
        int control = packet[3] >> 4 & 3;
        if ((packet[1] & 0x40) == 0 || (control & 1) == 0) {
            return -1;
        }
        int p = 4 + (control == 3 ? 1 + (packet[4] & 255) : 0);
        if (p + 14 > TS || packet[p] != 0 || packet[p + 1] != 0 || packet[p + 2] != 1) {
            return -1;
        }
        int stream = packet[p + 3] & 255;
        if (stream < 0xe0 || stream > 0xef || (packet[p + 7] & 0x80) == 0) {
            return -1;
        }
        return (long) (packet[p + 9] >> 1 & 7) << 30 | (packet[p + 10] & 255L) << 22 | (packet[p + 11] & 254L) << 14
                | (packet[p + 12] & 255L) << 7 | (packet[p + 13] & 255L) >> 1;
    }

    /** The PMT's PID in the PAT in this packet, or -1. */
    private static int pmtPid(byte[] packet) {
        int control = packet[3] >> 4 & 3;
        if ((packet[1] & 0x40) == 0 || (control & 1) == 0) {
            return -1;
        }
        int payload = 4 + (control == 3 ? 1 + (packet[4] & 255) : 0);
        if (payload >= TS) {
            return -1;
        }
        int section = payload + 1 + (packet[payload] & 255);
        int program = section + 8;
        return program + 4 > TS ? -1 : (packet[program + 2] & 0x1f) << 8 | packet[program + 3] & 255;
    }
}
