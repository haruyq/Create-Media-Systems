package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.BlockPos;

/**
 * A record/announcement plays on a native player, whose PCM goes through Minecraft's existing sound engine. Every
 * stream of one playback on this client (a jukebox and its speakers) taps the same player, so a speaker linked
 * mid-way plays the very samples the others do, at the same moment, instead of seeking a player of its own. The
 * streams of a display's playback share a player with video, whose pictures the display shows (see {@link #screen}).
 */
public final class NativeAudio {
    private static final long OPEN_TIMEOUT_NANOS = 90_000_000_000L;
    // Let the final four 100 ms OpenAL chunks finish before returning EOF.
    private static final long END_TAIL_NANOS = 500_000_000L;
    /** Longest a player waits, ready, for the streams joining it to tap it (see Shared#allTapped). */
    private static final long TAP_WAIT_NANOS = 1_000_000_000L;
    /** How far a playback may start off the server's position before it seeks there; seeking stalls a moment. */
    private static final long SYNC_TOLERANCE_NANOS = 300_000_000L;
    /** Longest a loaded playback waits, paused, for the server to say play: the server's barrier, and then some. */
    private static final long GO_TIMEOUT_NANOS = 15_000_000_000L;
    /** How often a playing video on demand checks how far its player is off where it should be (see Shared#correct). */
    private static final long CHECK_NANOS = 1_000_000_000L;
    /** Before the first check: VLC takes a moment to get going after it is told to play. */
    private static final long START_GRACE_NANOS = 3_000_000_000L;
    /**
     * Drift past this is sought away, at most once per cooldown, as a seek stalls a moment itself. Never made up by
     * playing faster or slower: VlcAudio puts each sample where VLC's timestamps say, which off normal speed no longer
     * match the samples, so it dropped and repeated bits of every read and the sound went harsh. Only past a stall
     * (a download falling behind), well above how far VLC's time and what is heard differ anyway.
     * ponytail: a connection that stalls longer than that on every seek keeps seeking every cooldown; back off if
     * that is ever seen.
     */
    private static final long SEEK_DRIFT_MILLIS = 3000;
    private static final long SEEK_COOLDOWN_NANOS = 10_000_000_000L;
    /** The last moments of a track aren't checked: VLC's time stops at its end. */
    private static final long END_MARGIN_MILLIS = 2000;
    /** The player each track's latest playback plays on, per Blu-ray player showing it; guarded by itself. */
    private static final Map<Key, Shared> SHARED = new HashMap<>();

    private NativeAudio() {
    }

    /**
     * The server's word to play: from {@code seconds} in, as of {@code at} (System.nanoTime) here, the trip from the
     * server allowed for (see Streams#started).
     */
    record Go(double seconds, long at) {
        /** Where the track is at {@code now} (ns in). */
        long position(long now) {
            return Math.max(0, Math.round(seconds * 1e9) + now - at);
        }
    }

    /**
     * {@code playback} is what plays it on the server (see {@link Shared#join}); null if unknown. {@code display} is
     * the Blu-ray player whose video it is, if any. {@code loaded} is told the track's length in seconds (0 if
     * unknown or live) once it is loaded; with {@code go}, its player then waits, paused, for the server's word to
     * play, so every listener starts it together (see Streams#started).
     */
    static AudioStream open(URI source, @Nullable Object playback, @Nullable Streams.Video video, double startSeconds,
                            long requestedAt, BooleanSupplier cancelled, Streams.Channel channel,
                            @Nullable CompletableFuture<Go> go, DoubleConsumer loaded) throws IOException {
        if (Vlc.MISSING != null) {
            throw new IOException(Vlc.MISSING);
        }
        return open(() -> new VlcPlayer(video, requestedAt), source, playback, video == null ? null : video.player(),
                startSeconds, requestedAt, cancelled, channel, go, loaded);
    }

    /**
     * How many seconds the video of the Blu-ray player at {@code pos} has left as it plays here, by this computer's
     * clock; -1 if it plays nothing here, live, or of unknown length.
     */
    static double remaining(BlockPos pos) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if (pos.equals(shared.key.display()) && shared.playing.isDone()
                        && !shared.playing.isCompletedExceptionally() && !shared.live && shared.owner.seconds() > 0) {
                    return shared.owner.seconds() - (System.nanoTime() - shared.origin) / 1e9;
                }
            }
        }
        return -1;
    }

    /** The video of the Blu-ray player at {@code pos} as it plays here (its displays show it); null if it doesn't. */
    @Nullable
    public static VlcPlayer.Frame screen(BlockPos pos) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if (pos.equals(shared.key.display()) && shared.owner instanceof VlcPlayer player) {
                    return player.frame;
                }
            }
        }
        return null;
    }

    @Nullable
    static VlcPlayer lighting(BlockPos pos) {
        return lighting(pos, null);
    }

    @Nullable
    static VlcPlayer lighting(BlockPos pos, @Nullable String track) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if ((pos.equals(shared.key.display()) || pos.equals(shared.playback))
                        && (track == null || shared.key.source().toString().equals(track))
                        && shared.playing.isDone() && !shared.playing.isCompletedExceptionally()
                        && shared.owner instanceof VlcPlayer player) {
                    return player;
                }
            }
        }
        return null;
    }

    static boolean lightingLive(BlockPos pos, String track) {
        synchronized (SHARED) {
            return SHARED.values().stream().anyMatch(shared -> shared.live && shared.key.source().toString().equals(track)
                    && (pos.equals(shared.key.display()) || pos.equals(shared.playback)));
        }
    }

    /** Every Blu-ray player's video playing here plays through {@code bands} of its player (see Equalizer#set). */
    public static void equalize(Function<BlockPos, float[]> bands) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if (shared.key.display() != null && shared.owner instanceof VlcPlayer player) {
                    player.audio.equalizer.set(bands.apply(shared.key.display()));
                }
            }
        }
    }

    /**
     * Joins the player of the same playback of {@code source} on this client if there is one, else opens one from
     * {@code players} (VLC's, or a test's stand-in).
     */
    static AudioStream open(Supplier<Backend> players, URI source, @Nullable Object playback,
                                    @Nullable BlockPos display, double startSeconds, long requestedAt,
                                    BooleanSupplier cancelled, Streams.Channel channel,
                                    @Nullable CompletableFuture<Go> go, DoubleConsumer loaded) throws IOException {
        long origin = requestedAt - Math.round(startSeconds * 1e9);
        Key key = new Key(source, display);
        Shared shared;
        boolean opener = false;
        synchronized (SHARED) {
            shared = SHARED.get(key);
            if (shared == null || !shared.join(origin, playback, startSeconds > 0)) {
                shared = new Shared(key, playback, players.get(), origin);
                SHARED.put(key, shared);
                opener = true;
            }
        }
        if (opener) {
            return open(shared, startSeconds, requestedAt, cancelled, channel, go, loaded);
        }
        // Tap it now, while it may still be loading: it starts only once every stream joining it hears it, so the
        // speakers of a stereo pair start together, not one after the other.
        Owned stream;
        try {
            stream = new Owned(shared, shared.owner.tap(channel));
        } catch (IOException | RuntimeException | LinkageError error) {
            shared.leave();
            throw error instanceof IOException io ? io : new IOException("Native playback failed to initialize", error);
        }
        shared.tapped();
        while (!shared.playing.isDone()) {
            if (cancelled.getAsBoolean()) {
                stream.close();
                throw new IOException("Native playback was cancelled");
            }
            sleep();
        }
        if (shared.playing.isCompletedExceptionally()) {
            // Its opener failed or gave up (it may only have been stopped): open one of our own.
            stream.close();
            return open(players, source, playback, display, startSeconds, requestedAt, cancelled, channel, go, loaded);
        }
        if (!cancelled.getAsBoolean()) {
            loaded.accept(shared.owner.seconds());
        }
        return stream;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new IOException("Native playback was cancelled");
        }
    }

    private static void sleep() throws IOException {
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Native playback was interrupted", interrupted);
        }
    }

    /** Small boundary around the native lifetime, so cancellation and EOF can be verified without a game. */
    interface Backend {
        AudioStream tap(Streams.Channel channel) throws IOException;

        /** Fails with a reason safe to show the player. */
        void start(URI source) throws IOException;

        Status status();

        void seek(long millis);

        void resume();

        void release();

        /** {@code source} would not open (not cancelled): drop anything kept of it that may be stale. */
        default void failed(URI source) {
        }

        /** The track's length in seconds once it is ready; 0 if unknown or live. */
        default double seconds() {
            return 0;
        }

        /** How far into the track it plays (ms); -1 if it can't tell. */
        default long time() {
            return -1;
        }
    }

    record Status(boolean ready, boolean live, boolean seekable, boolean ended, boolean broken) {
        static final Status WAITING = new Status(false, false, false, false, false);
        static final Status BROKEN = new Status(false, false, false, false, true);
    }

    /** VLC's network MediaType.STREAM includes ordinary HTTP VOD, so it cannot identify a live broadcast. */
    static boolean classifyLive(boolean assumedLive, boolean ready, long duration, boolean seekable) {
        return assumedLive || !(ready && duration > 0 && seekable);
    }

    private static AudioStream open(Shared shared, double startSeconds, long requestedAt, BooleanSupplier cancelled,
                                    Streams.Channel channel, @Nullable CompletableFuture<Go> go,
                                    DoubleConsumer loaded) throws IOException {
        Backend owner = shared.owner;
        Owned stream = null;
        try {
            checkCancelled(cancelled);
            // Install the callback before starting: VLC never briefly plays on the PC's default audio device.
            stream = new Owned(shared, owner.tap(channel));
            shared.tapped();
            owner.start(shared.key.source());
            long deadline = System.nanoTime() + OPEN_TIMEOUT_NANOS;
            Status state;
            while (!(state = owner.status()).ready()) {
                checkCancelled(cancelled);
                if (state.broken() || state.ended()) {
                    throw new IOException("The native player could not open this source");
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("The native player timed out opening this source");
                }
                sleep();
            }
            if (shared.diagnostics != null) {
                shared.diagnostics.ready();
            }
            // A local file is ready at once: give the streams that joined meanwhile the moment it takes to tap it.
            long tapDeadline = System.nanoTime() + TAP_WAIT_NANOS;
            while (!shared.allTapped() && System.nanoTime() < tapDeadline && !cancelled.getAsBoolean()) {
                sleep();
            }
            checkCancelled(cancelled);
            loaded.accept(owner.seconds());
            // Paused until the server says to play, and from where: the top once every listener has a fresh track
            // loaded, so a slower download doesn't leave one behind; at once, from where it is, for one already
            // playing (see Streams#started). A live stream has no place in it to agree on.
            long goDeadline = System.nanoTime() + GO_TIMEOUT_NANOS;
            while (go != null && !state.live() && !go.isDone() && System.nanoTime() < goDeadline) {
                checkCancelled(cancelled);
                sleep();
            }
            long now = System.nanoTime();
            Go told = go == null ? null : go.getNow(null);
            // Without the server's word (an announcement, or it never came): where the server was when it sent the
            // stream, plus the time it took to load here.
            long target = told != null ? told.position(now)
                    : startSeconds > 0 ? Math.round(startSeconds * 1e9) + Math.max(0, now - requestedAt) : 0;
            boolean vod = !state.live() && state.seekable();
            if (vod && target >= SYNC_TOLERANCE_NANOS) {
                owner.seek(target / 1_000_000);
            }
            owner.resume();
            if (shared.diagnostics != null) {
                shared.diagnostics.playing(told != null, target / 1e9);
            }
            // Where the track should be from now on, by this computer's clock: what drift is measured against.
            shared.origin = now - (vod ? target : 0);
            shared.nextCheck = now + START_GRACE_NANOS;
            shared.seekedAt = now;
            shared.live = state.live();
            shared.seekable = state.seekable();
            shared.playing.complete(null);
            return stream;
        } catch (IOException | RuntimeException | LinkageError error) {
            shared.playing.completeExceptionally(error);
            if (shared.diagnostics != null && !cancelled.getAsBoolean()) {
                shared.diagnostics.failed(error instanceof IOException ? error.getMessage()
                        : "Native playback failed to initialize");
            }
            if (!cancelled.getAsBoolean()) {
                // A cached resolution or kept audio may be what failed (a signed URL that expired): the next try
                // resolves again.
                owner.failed(shared.key.source());
            }
            if (stream != null) {
                stream.close();
            } else {
                shared.leave();
            }
            if (error instanceof IOException io) {
                throw io;
            }
            // Native/resolver error text can contain a signed URL or credentials; keep the user-facing error safe.
            throw new IOException("Native playback failed to initialize", error);
        }
    }

    /** A track, and the Blu-ray player showing it (null for none: a record's audio alone). */
    private record Key(URI source, @Nullable BlockPos display) {
    }

    /** A player and the streams playing it; released when the last of them closes. */
    private static final class Shared {
        final Key key;
        @Nullable
        final Object playback;
        final Backend owner;
        /** What its video's playback took (see Diagnostics); null for a record's. */
        @Nullable
        final Diagnostics diagnostics;
        /** Done once it plays; failed if it never will. */
        final CompletableFuture<Void> playing = new CompletableFuture<>();
        /** When the track's top was (System.nanoTime), to tell this playback from a later one of the same track. */
        volatile long origin;
        volatile boolean live;
        volatile boolean seekable;
        private int users = 1;
        private int tapped;
        /** When the next drift check is due (see correct); guarded by this. */
        private long nextCheck;
        /** When it last sought; on the sound thread (see correct). */
        private long seekedAt;

        Shared(Key key, @Nullable Object playback, Backend owner, long origin) {
            this.key = key;
            this.playback = playback;
            this.owner = owner;
            this.origin = origin;
            diagnostics = owner instanceof VlcPlayer player ? player.diagnostics : null;
        }

        /**
         * Whether a stream asked to play from the track's top at {@code origin} plays this; if so, it is a user.
         * One joining the same source mid-way ({@code midway}) always does: the server's position for it lags the
         * one we hear whenever the server falls below 20 TPS. A fresh start goes by time, so a restart is new.
         */
        synchronized boolean join(long origin, @Nullable Object playback, boolean midway) {
            boolean sameSource = midway && playback != null && playback.equals(this.playback);
            if (users == 0 || playing.isCompletedExceptionally() || !sameSource && !live
                    && Math.abs(origin - this.origin) / 1e9 >= SyncClock.SAME_PLAYBACK_SECONDS) {
                return false;
            }
            users++;
            return true;
        }

        synchronized void tapped() {
            tapped++;
        }

        /**
         * Every {@link #CHECK_NANOS}, from any of its streams' reads: how far its player is off where the track should
         * be by now ({@link #origin}, set where the server said as it started). Stalling to download (a slow
         * connection) leaves it behind: past {@link #SEEK_DRIFT_MILLIS}, it seeks to where it should be. Measured
         * by this computer's clock, never the server's, whose count falls behind whenever it runs below 20 TPS: every
         * client started where the server said, and clocks keep pace with each other.
         */
        void correct() {
            long now = System.nanoTime();
            synchronized (this) {
                if (now - nextCheck < 0 || !playing.isDone() || playing.isCompletedExceptionally()) {
                    return;
                }
                nextCheck = now + CHECK_NANOS;
            }
            long at = owner.time();
            long expected = (now - origin) / 1_000_000;
            double length = owner.seconds();
            if (diagnostics != null && now - diagnostics.playing >= Diagnostics.REPORT_AFTER_NANOS) {
                diagnostics.send();
            }
            if (live || !seekable || at < 0 || length > 0 && expected > length * 1000 - END_MARGIN_MILLIS) {
                return;
            }
            boolean seek = Math.abs(expected - at) >= SEEK_DRIFT_MILLIS && now - seekedAt >= SEEK_COOLDOWN_NANOS;
            if (diagnostics != null) {
                diagnostics.drift(expected - at, seek);
            }
            if (seek) {
                seekedAt = now;
                owner.seek(expected);
            }
        }

        /** Whether every stream playing it has tapped it, so all of them hear it from its first sample. */
        synchronized boolean allTapped() {
            return tapped >= users;
        }

        void leave() {
            synchronized (this) {
                if (--users > 0) {
                    return;
                }
            }
            synchronized (SHARED) {
                SHARED.remove(key, this);
            }
            owner.release();
        }
    }

    static final class Owned implements AudioStream, DeviceClock.Timed {
        private final Shared shared;
        private final AudioStream tap;
        private final AtomicBoolean closed = new AtomicBoolean();
        private long endedAt;
        /** It played to the end, or broke off: closed for good, not just dropped by the sound engine. */
        private volatile boolean done;

        private Owned(Shared shared, AudioStream tap) {
            this.shared = shared;
            this.tap = tap;
        }

        @Override
        public AudioFormat getFormat() {
            return tap.getFormat();
        }

        @Override
        public long attach(int source, long deviceNow) {
            return tap instanceof DeviceClock.Timed timed ? timed.attach(source, deviceNow) : 0;
        }

        @Override
        public boolean advancesWhilePaused() {
            return tap instanceof DeviceClock.Timed timed && timed.advancesWhilePaused();
        }

        /** Whether it played to its end or broke off, as opposed to being closed by whoever played it. */
        boolean done() {
            return done;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            if (closed.get()) {
                return ByteBuffer.allocateDirect(0);
            }
            Status state = shared.owner.status();
            if (state.broken()) {
                done = true;
                close();
                throw new IOException("Native playback was interrupted");
            }
            if (state.ended()) {
                if (endedAt == 0) {
                    endedAt = System.nanoTime();
                } else if (System.nanoTime() - endedAt >= END_TAIL_NANOS && VlcAudio.drained(tap)) {
                    done = true;
                    close();
                    return ByteBuffer.allocateDirect(0);
                }
            } else {
                endedAt = 0;
            }
            shared.correct();
            return tap.read(size);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    tap.close();
                } catch (IOException ignored) {
                    // Still release the owned native player if the tap has already gone away.
                } finally {
                    shared.leave();
                }
            }
        }
    }
}
