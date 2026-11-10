package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.stream.UrlPolicy;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

/**
 * One download of an API stream, read by every stream of the same audio on this client. A source's speakers all
 * play the same audio, and with a download each, many speakers ran the API out of streams (it allows MAX_STREAMS at
 * once and answers "Too many streams" past that). A pump thread reads it into a ring buffer, at most
 * {@link #AHEAD_BYTES} past the slowest reader, keeping {@link #BEHIND_BYTES} before that for streams that join
 * later: a speaker coming into range starts where the music is heard, a few seconds behind what the others have
 * read (the sound engine queues that much).
 */
final class Feed {
    /**
     * Reconnects tried in a row before a broken-off stream is reported. Proxies cut long responses (Cloudflare
     * resets them after about 3 minutes), so a break mid-track usually just means "reconnect and carry on".
     */
    private static final int MAX_RESUMES = 3;
    private static final int AHEAD_BYTES = Pcm.BYTES_PER_SECOND * 15;
    private static final int BEHIND_BYTES = Pcm.BYTES_PER_SECOND * 10;
    /** A reader may start this far past the download and wait for it (a speaker asked a tick after its pair). */
    private static final int JOIN_AHEAD_BYTES = Pcm.BYTES_PER_SECOND * 2;
    private static final int CHUNK_BYTES = 64 * 1024;
    /** The feed each key's new readers join; guarded by itself, and taken before any feed's own lock. */
    private static final Map<String, Feed> SHARED = new HashMap<>();

    private final String key;
    private final double startSeconds;
    // One chunk more than both: the pump writes a whole chunk once under AHEAD_BYTES (see write).
    private final byte[] ring = new byte[AHEAD_BYTES + BEHIND_BYTES + CHUNK_BYTES];
    private final List<Reader> readers = new ArrayList<>();
    /** Bytes downloaded; byte {@code i} is at {@code ring[i % ring.length]}. */
    private long received;
    /** The pump ended: the track is over, or the download failed. */
    private boolean done;
    /** Why the download could not start; null if it did. */
    @Nullable
    private IOException failure;
    /** Every reader left; the pump stops. */
    private boolean closed;
    /** The download the pump reads now; closed with the feed, as a read blocked on it would never end. */
    @Nullable
    private volatile InputStream current;

    private Feed(String key, double startSeconds) {
        this.key = key;
        this.startSeconds = startSeconds;
    }

    /**
     * A reader of {@code key}'s audio from {@code startSeconds} into the track: of the download already running if it
     * still has that part, else of a new one opened through {@code open}, which also reconnects it if it breaks off
     * mid-track; only after {@link #MAX_RESUMES} failed tries in a row does the error go to its readers'
     * {@code onInterrupted}.
     */
    static Reader read(String key, double startSeconds, Resume open,
                       Consumer<String> onInterrupted) {
        synchronized (SHARED) {
            Feed shared = SHARED.get(key);
            Reader reader = shared == null ? null : shared.join(startSeconds, onInterrupted);
            if (reader == null) {
                Feed feed = new Feed(key, startSeconds);
                reader = feed.join(startSeconds, onInterrupted);
                SHARED.put(key, feed);
                feed.start(open);
            }
            return reader;
        }
    }

    /** A reader from {@code seconds} into the track; null if this download doesn't have that part (any more). */
    @Nullable
    private synchronized Reader join(double seconds, Consumer<String> onInterrupted) {
        long at = Pcm.bytes(seconds - startSeconds);
        // Behind the slowest reader by up to BEHIND_BYTES is still in the ring: write never gets further ahead.
        if (closed || at < 0 || at > received + JOIN_AHEAD_BYTES
                || !readers.isEmpty() && at < slowest() - BEHIND_BYTES) {
            return null;
        }
        Reader reader = new Reader(at, onInterrupted);
        readers.add(reader);
        return reader;
    }

    private long slowest() {
        long slowest = received;
        for (Reader reader : readers) {
            slowest = Math.min(slowest, reader.position);
        }
        return slowest;
    }

    private void start(Resume open) {
        Thread.ofPlatform().daemon().name("CMS stream").start(() -> {
            try {
                pump(open);
            } catch (IOException e) {
                List<Reader> told;
                synchronized (this) {
                    told = closed ? List.of() : List.copyOf(readers);
                }
                unshare();
                told.forEach(reader -> reader.onInterrupted.accept(UrlPolicy.reason(e)));
            } finally {
                synchronized (this) {
                    done = true;
                    notifyAll();
                }
            }
        });
    }

    private void pump(Resume open) throws IOException {
        InputStream in;
        try {
            in = open.from(0);
        } catch (IOException e) {
            synchronized (this) {
                failure = e;
            }
            unshare();
            return;
        }
        byte[] buffer = new byte[CHUNK_BYTES];
        int failures = 0;
        while (true) {
            IOException broke;
            try (InputStream reading = in) {
                current = reading;
                if (isClosed()) {
                    return;
                }
                for (int n; (n = reading.read(buffer)) > 0; ) {
                    write(buffer, n);
                    failures = 0;
                }
                return;
            } catch (IOException e) {
                broke = e;
            }
            in = null;
            while (in == null) {
                if (isClosed() || ++failures > MAX_RESUMES) {
                    throw broke;
                }
                try {
                    Thread.sleep(500L * failures);
                    long at = receivedSoFar();
                    if (at % 2 != 0) {
                        write(new byte[1], 1); // finish the half sample, so the resumed audio stays 16-bit aligned
                        at++;
                    }
                    LogUtils.getLogger().info("Stream broke off ({}); resuming at {}s", UrlPolicy.reason(broke),
                            at / Pcm.BYTES_PER_SECOND);
                    in = open.from(at / (double) Pcm.BYTES_PER_SECOND);
                } catch (IOException e) {
                    broke = e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw broke;
                }
            }
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private synchronized long receivedSoFar() {
        return received;
    }

    /** Adds to the ring, first waiting while the slowest reader is {@link #AHEAD_BYTES} behind; fails once closed. */
    private synchronized void write(byte[] bytes, int length) throws IOException {
        try {
            while (!closed && received - slowest() >= AHEAD_BYTES) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (closed) {
            throw new IOException("Stream closed");
        }
        for (int i = 0; i < length; ) {
            int at = (int) (received % ring.length);
            int n = Math.min(length - i, ring.length - at);
            System.arraycopy(bytes, i, ring, at, n);
            i += n;
            received += n;
        }
        notifyAll();
    }

    private void unshare() {
        synchronized (SHARED) {
            SHARED.remove(key, this);
        }
    }

    /** One stream's place in the feed. Never waits on the download unless a method says so. */
    final class Reader {
        private long position;
        private boolean left;
        private final Consumer<String> onInterrupted;

        private Reader(long position, Consumer<String> onInterrupted) {
            this.position = position;
            this.onInterrupted = onInterrupted;
        }

        int available() {
            synchronized (Feed.this) {
                return (int) Math.max(0, received - position);
            }
        }

        /** The download ended (track over, or failed), so no more bytes will come. */
        boolean done() {
            synchronized (Feed.this) {
                return done;
            }
        }

        /** Why the download could not start; null if it did (or hasn't yet). */
        @Nullable
        IOException failure() {
            synchronized (Feed.this) {
                return failure;
            }
        }

        byte[] read(int max) {
            synchronized (Feed.this) {
                byte[] bytes = new byte[(int) Math.min(max, Math.max(0, received - position))];
                for (int i = 0; i < bytes.length; ) {
                    int at = (int) (position % ring.length);
                    int n = Math.min(bytes.length - i, ring.length - at);
                    System.arraycopy(ring, at, bytes, i, n);
                    i += n;
                    position += n;
                }
                Feed.this.notifyAll();
                return bytes;
            }
        }

        long position() {
            synchronized (Feed.this) {
                return position;
            }
        }

        double mediaSeconds(long bytes) {return startSeconds+bytes/(double)Pcm.BYTES_PER_SECOND;}

        @Nullable
        byte[] peek(long from, int length) {
            synchronized (Feed.this) {
                if (left || from < Math.max(0, received - ring.length) || length < 0 || from + length > received) {
                    return null;
                }
                byte[] bytes = new byte[length];
                for (int i = 0; i < length; ) {
                    int at = (int) ((from + i) % ring.length);
                    int n = Math.min(length - i, ring.length - at);
                    System.arraycopy(ring, at, bytes, i, n);
                    i += n;
                }
                return bytes;
            }
        }

        /** Skips {@code bytes}, waiting until the download has got there (or ended, or this closed). */
        void skip(long bytes) {
            synchronized (Feed.this) {
                position += bytes;
                Feed.this.notifyAll();
                awaitBytes(0, Long.MAX_VALUE);
            }
        }

        /** Waits until {@code bytes} past here are downloaded, at most until {@code deadline} (currentTimeMillis). */
        void prebuffer(int bytes, long deadline) {
            synchronized (Feed.this) {
                awaitBytes(bytes, deadline);
            }
        }

        private void awaitBytes(int bytes, long deadline) {
            try {
                long wait;
                while (!left && !done && received - position < bytes
                        && (wait = deadline - System.currentTimeMillis()) > 0) {
                    Feed.this.wait(Math.min(wait, 1000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Leaves the feed; the last reader out stops the download. */
        void close() {
            boolean last;
            synchronized (Feed.this) {
                if (!readers.remove(this)) {
                    return;
                }
                left = true;
                last = readers.isEmpty();
                closed |= last;
                Feed.this.notifyAll();
            }
            if (last) {
                unshare();
                InputStream reading = current;
                if (reading != null) {
                    try {
                        reading.close();
                    } catch (IOException ignored) {
                        // closing a download can't meaningfully fail
                    }
                }
            }
        }
    }

    /** Reopens the stream {@code offset} seconds into it. */
    interface Resume {
        InputStream from(double offset) throws IOException;
    }
}
