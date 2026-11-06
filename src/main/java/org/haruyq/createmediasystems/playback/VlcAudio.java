package org.haruyq.createmediasystems.playback;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.stream.Streams;

import com.sun.jna.Pointer;

import net.minecraft.client.sounds.AudioStream;

/**
 * A VLC player's audio, taken from VLC as it plays instead of going to the speakers of the computer: every stream of
 * one playback (a jukebox, a display and their speakers) plays exactly what the player decodes, each lined up with
 * VLC's clock, so they stay in step with each other and with the player's video.
 * <p>
 * What a stream plays is a matter of time alone, never of its history: each read puts the sample due at each moment
 * there, to the sample. A stream that lined up once and then let its clock drift a little (as these did, up to 10 ms)
 * kept whatever offset VLC running a moment late or a stall had left it with, each stream its own, so speakers
 * playing one channel drifted milliseconds apart and sounded hollow; pausing the game, which made every stream catch
 * up at once, put them back in step, so opening a menu changed how they sounded.
 */
final class VlcAudio {
    /** Each read hands the engine this much; it keeps four reads queued, so about that much plays ahead. */
    private static final int CHUNK_BYTES = Pcm.BYTES_PER_SECOND / 10;
    /**
     * Without OpenAL's clock (see DeviceClock): about how far ahead of now the engine plays what a read returns
     * (three and a half chunks queued before it), and how far off that guess may be before it is lined up again.
     */
    private static final long QUEUED_MICROS = 350_000;
    private static final long GUESSED_TOLERANCE_MICROS = 40_000;
    /** With OpenAL's clock, every read lines up to the nearest sample. */
    private static final long HALF_SAMPLE_MICROS = 1_000_000L / Pcm.RATE / 2;
    /**
     * A block VLC times closer than this to where the last one ended is taken to follow it seamlessly: VLC's
     * timestamps wobble by a hair, and lining up to them would make every stream skip or repeat a few samples there.
     */
    private static final long SEAMLESS_MICROS = 2_000;
    /** Audio this far behind is stale (VLC was paused or seeking), not just late: it is dropped. */
    private static final long STALE_MICROS = 1_000_000;
    /** A gap this short (1 ms) is clock drift, not VLC running dry: the last sample is held over it. */
    private static final int HOLD_BYTES = Pcm.BYTES_PER_SECOND / 1000;
    /** At most this much is kept for a reader that falls behind. */
    private static final int MAX_QUEUED_BYTES = Pcm.BYTES_PER_SECOND * 5;

    private final List<Stream> streams = new CopyOnWriteArrayList<>();
    /** What every stream hears it through; set by the player (see VlcPlayer#equalize). */
    final Equalizer equalizer = new Equalizer();
    /** When VLC played its first samples (System.nanoTime); 0 before. */
    volatile long firstSamples;
    /** Held by the player: VLC calls them as long as it plays, so they must outlive no reference. */
    final Vlc.AudioPlay play = (opaque, samples, count, pts) -> play(samples, count, pts);
    final Vlc.AudioPts flush = (opaque, pts) -> flush();
    /**
     * One moment on both OpenAL's clock and VLC's (when it is heard), shared by this player's streams so they map
     * time alike; on the sound thread. ponytail: taken once, so the two clocks drift apart (a few ms a minute at
     * worst) over a long video, equally for every stream; re-take it now and then if that is ever heard.
     */
    private long anchorDevice;
    private long anchorVlc;
    /**
     * VLC's audio as one seamless run: when its current run of samples started (VLC's clock, µs) and how many have
     * come since; on VLC's audio thread.
     */
    private long runPts;
    private long runSamples;

    /** Whether a stream from {@link #open} has played all it was given (the player waits for that before EOF). */
    static boolean drained(AudioStream tap) {
        if (!(tap instanceof Stream stream)) {
            return true;
        }
        synchronized (stream.queue) {
            return stream.queue.isEmpty();
        }
    }

    /** What VLC is asked to hand over: interleaved stereo, 16-bit (see VlcPlayer), which play() and Equalizer read. */
    static final int CHANNELS = 2;

    /** A new stream of the player's audio, as heard on {@code channel}. */
    AudioStream open(Streams.Channel channel) {
        Stream stream = new Stream(this, channel);
        streams.add(stream);
        return stream;
    }

    /** When audio OpenAL plays at {@code device} (its clock, ns) is heard, on VLC's clock (µs); 0 if it can't tell. */
    private synchronized long onVlcClock(long device) {
        if (anchorDevice == 0) {
            long[] nowAndLatency = DeviceClock.nowAndLatency();
            if (nowAndLatency == null) {
                return 0;
            }
            anchorDevice = nowAndLatency[0];
            anchorVlc = Vlc.LIB.libvlc_clock() + nowAndLatency[1] / 1000;
        }
        return anchorVlc + (device - anchorDevice) / 1000;
    }

    private void play(Pointer samples, int sampleCount, long pts) {
        if (firstSamples == 0) {
            firstSamples = System.nanoTime();
        }
        if (!streams.isEmpty()) {
            byte[] stereo = samples.getByteArray(0, sampleCount * 4);
            equalizer.process(stereo);
            offer(stereo, sampleCount, pts);
        }
    }

    /**
     * Hands every stream {@code count} stereo samples VLC timed at {@code pts}: on the seamless run if they follow it
     * within {@link #SEAMLESS_MICROS}, else (a seek, a gap) starting a new one at {@code pts}. Every stream gets the same
     * times, so they all put a sample at the same moment.
     */
    void offer(byte[] stereo, int count, long pts) {
        long next = runPts + runSamples * 1_000_000L / Pcm.RATE;
        if (runSamples == 0 || Math.abs(pts - next) > SEAMLESS_MICROS) {
            runPts = pts;
            runSamples = 0;
            next = pts;
        }
        runSamples += count;
        for (Stream stream : streams) {
            stream.offer(stereo, next);
        }
    }

    private void flush() {
        runSamples = 0;
        streams.forEach(Stream::clear);
    }

    /** One stream's share: mono, on VLC's clock, so each read lines up with when the engine will play it. */
    static final class Stream implements AudioStream, DeviceClock.Timed {
        private final VlcAudio audio;
        private final Streams.Channel channel;
        /** Mono chunks with the VLC time (microseconds) of their first sample; guarded by itself. */
        private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
        private int queuedBytes;
        // Keep recently queued samples so a brief menu pause can rebuild OpenAL's buffers without losing audio.
        private final ArrayDeque<Chunk> history = new ArrayDeque<>();
        private int historyBytes;
        private volatile boolean closed;
        /** The OpenAL source it plays on and when that starts (device clock, ns; 0 if it can't tell); see attach. */
        private int source;
        private long startAt;

        private record Chunk(byte[] pcm, long pts) {
        }

        /** How much of the head chunk has been played. */
        private int headOffset;
        /** The last sample played, held over a gap of up to a millisecond instead of silence. */
        private byte lastLow;
        private byte lastHigh;

        Stream(VlcAudio audio, Streams.Channel channel) {
            this.audio = audio;
            this.channel = channel;
        }

        void offer(byte[] stereo, long pts) {
            byte[] mono = new byte[stereo.length / 2];
            for (int i = 0, o = 0; i + 3 < stereo.length; i += 4, o += 2) {
                int left = (short) (stereo[i] & 0xff | stereo[i + 1] << 8);
                int right = (short) (stereo[i + 2] & 0xff | stereo[i + 3] << 8);
                int sample = switch (channel) {
                    case LEFT -> left;
                    case RIGHT -> right;
                    case MIX -> (left + right) / 2;
                };
                mono[o] = (byte) sample;
                mono[o + 1] = (byte) (sample >> 8);
            }
            synchronized (queue) {
                queue.addLast(new Chunk(mono, pts));
                queuedBytes += mono.length;
                while (queuedBytes - headOffset > MAX_QUEUED_BYTES) {
                    dropHead();
                }
            }
        }

        void clear() {
            synchronized (queue) {
                queue.clear();
                queuedBytes = 0;
                headOffset = 0;
                history.clear();
                historyBytes = 0;
            }
        }

        @Override
        public long attach(int source, long deviceNow) {
            this.source = source;
            startAt = deviceNow == 0 ? 0 : deviceNow + DeviceClock.START_DELAY_NANOS;
            synchronized (queue) {
                while (!history.isEmpty()) {
                    Chunk previous = history.removeLast();
                    queue.addFirst(previous);
                    queuedBytes += previous.pcm.length;
                }
                historyBytes = 0;
                headOffset = 0;
            }
            return startAt;
        }

        @Override
        public boolean advancesWhilePaused() {
            return true;
        }

        private void dropHead() {
            Chunk head = queue.removeFirst();
            queuedBytes -= head.pcm.length;
            headOffset = 0;
            history.addLast(head);
            historyBytes += head.pcm.length;
            while (historyBytes > Pcm.BYTES_PER_SECOND) {
                historyBytes -= history.removeFirst().pcm.length;
            }
        }

        @Override
        public AudioFormat getFormat() {
            return Pcm.FORMAT;
        }

        /**
         * Always a full chunk, never waiting (this is the sound engine's thread): the engine keeps a fixed number of
         * reads queued and stops a sound that runs dry. OpenAL's clock says when what it returns will be heard, so
         * that is when its samples are due on VLC's clock: samples still early wait behind silence, late ones go,
         * to the nearest sample at every read, so every stream of the player plays each sample at the same moment
         * whatever happened to it before. Without OpenAL's clock it guesses {@link #QUEUED_MICROS} from now, and only
         * samples off by more than that guess can be are moved.
         */
        @Override
        public ByteBuffer read(int size) {
            ByteBuffer out = ByteBuffer.allocateDirect(CHUNK_BYTES);
            if (closed) {
                return out.flip();
            }
            long heardAt = startAt == 0 ? 0
                    : audio.onVlcClock(DeviceClock.queueEnd(source, startAt, CHUNK_BYTES / 2));
            boolean exact = heardAt != 0;
            fill(out, exact ? heardAt : Vlc.LIB.libvlc_clock() + QUEUED_MICROS, exact);
            return out.clear();
        }

        /** Fills {@code out} with the samples due from {@code playsAt} (VLC's clock, µs) on; see {@link #read}. */
        void fill(ByteBuffer out, long playsAt, boolean exact) {
            synchronized (queue) {
                long early = exact ? HALF_SAMPLE_MICROS : GUESSED_TOLERANCE_MICROS;
                long late = exact ? HALF_SAMPLE_MICROS : STALE_MICROS;
                while (out.hasRemaining()) {
                    Chunk head = queue.peekFirst();
                    if (head == null) {
                        break; // VLC is behind (loading, paused): silence for the rest
                    }
                    long due = head.pts + headOffset / 2 * 1_000_000L / Pcm.RATE;
                    long at = playsAt + (out.position() / 2) * 1_000_000L / Pcm.RATE;
                    if (due < at - late) {
                        int gone = (int) Math.min(head.pcm.length - headOffset, (at - due) * Pcm.RATE / 1_000_000L * 2) & ~1;
                        headOffset += Math.max(gone, 2);
                        if (headOffset >= head.pcm.length) {
                            dropHead();
                        }
                    } else if (due > at + early) {
                        int gap = Math.max((int) Math.min(out.remaining(), (due - at) * Pcm.RATE / 1_000_000L * 2) & ~1, 2);
                        if (gap <= HOLD_BYTES) {
                            // OpenAL's clock and VLC's drift a sample apart every second or so: a zero there clicked.
                            for (int i = 0; i < gap; i += 2) {
                                out.put(lastLow).put(lastHigh);
                            }
                        } else {
                            out.position(out.position() + gap);
                        }
                    } else {
                        int n = Math.min(out.remaining(), head.pcm.length - headOffset);
                        out.put(head.pcm, headOffset, n);
                        headOffset += n;
                        lastLow = head.pcm[headOffset - 2];
                        lastHigh = head.pcm[headOffset - 1];
                        if (headOffset == head.pcm.length) {
                            dropHead();
                        }
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            clear();
            audio.streams.remove(this);
        }
    }
}
