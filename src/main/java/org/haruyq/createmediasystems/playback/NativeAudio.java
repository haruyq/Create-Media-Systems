package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.BlockPos;

/**
 * A record/announcement plays on a native player, whose PCM goes through Minecraft's existing sound engine. Every
 * stream of one playback on this client (a jukebox and its speakers) taps the same player, so a speaker linked
 * mid-way plays the very samples the others do, at the same moment, instead of seeking a player of its own. The
 * streams of a display's playback share a player with video, whose pictures the display shows (see
 * {@link Playback#frame}). Which player plays is the caller's choice (a {@link Backend}); here it is only shared.
 */
public final class NativeAudio {
    /** What the server waits for its listeners to load a track (see Streams#MAX_LOAD_TICKS). */
    private static final long OPEN_TIMEOUT_NANOS = Streams.MAX_LOAD_TICKS * PlaybackClock.NANOS_PER_TICK;
    // Let the final four 100 ms OpenAL chunks finish before returning EOF.
    private static final long END_TAIL_NANOS = 500_000_000L;
    /** Longest a player waits, ready, for the streams joining it to tap it (see Playback#allTapped). */
    private static final long TAP_WAIT_NANOS = 1_000_000_000L;
    /**
     * How long (ms) to wait before each new try at a source the native player could not open. A link the origin has
     * only just handed out may answer 403 for some seconds yet (YouTube holds it back for its pre-roll ad), longer
     * than the resolver was told to wait: a track first opened part way in, moments after the server asked about it,
     * failed every time, and stayed silent until it was played again.
     */
    private static final long[] RETRY_MILLIS = {2000, 3000, 5000, 8000};
    /** How far a playback may start off the server's position before it seeks there; seeking stalls a moment. */
    private static final long SYNC_TOLERANCE_NANOS = 300_000_000L;
    /** How often a playing video on demand checks how far off where it should be its player is (see Playback#correct). */
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
    private static final Hold TRACK_HOLD = new Hold(3000, 10_000_000_000L);
    /**
     * A moving disc's player is held closer: each listener rejoins a moving disc on its own after unloading, and a
     * loading stall must not leave one seconds behind.
     */
    private static final Hold DISC_HOLD = new Hold(1000, 3_000_000_000L);
    /** The last moments of a track aren't checked: VLC's time stops at its end. */
    private static final long END_MARGIN_MILLIS = 2000;
    /** The playbacks on a native player here, oldest first; guarded by itself. */
    private static final List<Playback> PLAYBACKS = new ArrayList<>();

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

    /** How far a playing track may drift off where it should be before it is sought there, at most once a cooldown. */
    private record Hold(long driftMillis, long cooldownNanos) {
    }

    /**
     * The native audio of {@code spec}, asked for at {@code requestedAt}, on a player from {@code players} (see
     * {@link #open}); a source that would not open is tried again a few times (see {@link #RETRY_MILLIS}).
     */
    static AudioStream play(Supplier<Backend> players, StreamSpec spec, long requestedAt, BooleanSupplier cancelled,
                            @Nullable CompletableFuture<Go> go) throws IOException {
        return retrying(() -> open(players, spec, requestedAt, cancelled, go), cancelled, RETRY_MILLIS);
    }

    /** The native player could not open a source: it may open a moment later (see {@link #RETRY_MILLIS}). */
    static final class Unopened extends IOException {
        Unopened() {
            super("The native player could not open this source");
        }
    }

    interface Opening {
        AudioStream open() throws IOException;
    }

    /**
     * {@code opening}'s stream; a source that would not open is tried again after each of {@code waits} (ms), and
     * fails for good after the last, or once {@code cancelled}.
     */
    static AudioStream retrying(Opening opening, BooleanSupplier cancelled, long... waits) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return opening.open();
            } catch (Unopened notYet) {
                if (attempt >= waits.length) {
                    throw notYet;
                }
                long until = System.nanoTime() + waits[attempt] * 1_000_000L;
                while (System.nanoTime() - until < 0) {
                    checkCancelled(cancelled);
                    sleep();
                }
            }
        }
    }

    /**
     * How far into its track a playback plays here, by this computer's clock, and how long the track is (0: unknown,
     * or live); {@code loading} while it loads here, or waits for the others to.
     */
    public record Progress(boolean loading, double seconds, double length) {
    }

    /**
     * The playback shown by (a Blu-ray player, a hi-fi stack) or played from (a source) the block at {@code pos}, of
     * {@code track} if not null: the newest that plays here, else the newest still loading; null if none.
     */
    @Nullable
    public static Playback playback(BlockPos pos, @Nullable String track) {
        return find(each -> (pos.equals(each.display) || pos.equals(each.id.source()))
                && (track == null || each.id.track().equals(track)));
    }

    /** The same, of whatever track. */
    @Nullable
    public static Playback playback(BlockPos pos) {
        return playback(pos, null);
    }

    /** The playback of the moving disc {@code disc} (see StreamSpec#movingDisc); null if none. */
    @Nullable
    public static Playback playback(UUID disc) {
        return find(each -> disc.equals(each.id.disc()));
    }

    /** Every playback here. */
    public static List<Playback> playbacks() {
        synchronized (PLAYBACKS) {
            return List.copyOf(PLAYBACKS);
        }
    }

    @Nullable
    private static Playback find(Predicate<Playback> of) {
        Playback loading = null;
        synchronized (PLAYBACKS) {
            for (int i = PLAYBACKS.size() - 1; i >= 0; i--) {
                Playback each = PLAYBACKS.get(i);
                if (each.failed() || !of.test(each)) {
                    continue;
                }
                if (each.plays()) {
                    return each;
                }
                loading = loading == null ? each : loading;
            }
        }
        return loading;
    }

    /**
     * Joins the player of the same playback of {@code spec} (see PlaybackId#same) on this client if there is one, else
     * opens one from {@code players} (VLC's, or a test's stand-in).
     */
    static AudioStream open(Supplier<Backend> players, StreamSpec spec, long requestedAt, BooleanSupplier cancelled,
                            @Nullable CompletableFuture<Go> go) throws IOException {
        URI source = URI.create(spec.track());
        PlaybackId id = spec.playback(requestedAt);
        BlockPos display = spec.video() == null ? null : spec.video().player();
        Playback shared = null;
        Playback opened = null;
        synchronized (PLAYBACKS) {
            for (int i = PLAYBACKS.size() - 1; i >= 0 && shared == null; i--) {
                Playback each = PLAYBACKS.get(i);
                shared = Objects.equals(display, each.display) && each.join(id) ? each : null;
            }
            if (shared == null) {
                opened = new Playback(id, display, players.get(), spec.disc());
                PLAYBACKS.add(opened);
            }
        }
        if (opened != null) {
            return open(opened, source, spec, requestedAt, cancelled, go);
        }
        // Tap it now, while it may still be loading: it starts only once every stream joining it hears it, so the
        // speakers of a stereo pair start together, not one after the other.
        Owned stream;
        try {
            stream = new Owned(shared, shared.owner.tap(spec.channel()));
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
        if (shared.failed()) {
            // Its opener failed or gave up (it may only have been stopped): open one of our own.
            stream.close();
            return open(players, spec, requestedAt, cancelled, go);
        }
        if (!cancelled.getAsBoolean()) {
            spec.started().accept(shared.owner.seconds());
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

        /**
         * Opens {@code source}, paused once open; at where {@code opensAt} says (s, asked as it opens) when its
         * playback has a clock of its own (a moving disc's), else at the top, to be sought where the server says.
         * Fails with a reason safe to show the player.
         */
        void start(URI source, @Nullable DoubleSupplier opensAt) throws IOException;

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

        /** Whether {@code tap} has played all it was given, so its stream may end (see Owned#read). */
        default boolean drained(AudioStream tap) {
            return true;
        }

        /** Its video's pictures; null for none. */
        @Nullable
        default VideoFrame frame() {
            return null;
        }

        /** Plays through {@code tone} from now on (see Equalizer). */
        default void equalize(Tone tone) {
        }

        /** What its video's playback took (see Diagnostics); null for none. */
        @Nullable
        default Diagnostics diagnostics() {
            return null;
        }

        /** Its taps, offered its sound (after its tone) and pictures as they play; null for none. */
        @Nullable
        default MediaTaps taps() {
            return null;
        }

        /** Whether its media came in lately (as of {@code now}): a live stream still flowing. */
        default boolean recentMedia(long now) {
            return false;
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

    private static AudioStream open(Playback shared, URI source, StreamSpec spec, long requestedAt,
                                    BooleanSupplier cancelled, @Nullable CompletableFuture<Go> go) throws IOException {
        Backend owner = shared.owner;
        double startSeconds = spec.startSeconds();
        Owned stream = null;
        try {
            checkCancelled(cancelled);
            // Install the callback before starting: VLC never briefly plays on the PC's default audio device.
            stream = new Owned(shared, owner.tap(spec.channel()));
            shared.tapped();
            owner.start(source, shared.disc == null ? null : shared.disc.seconds());
            long deadline = System.nanoTime() + OPEN_TIMEOUT_NANOS;
            Status state;
            while (!(state = owner.status()).ready()) {
                checkCancelled(cancelled);
                if (state.broken() || state.ended()) {
                    throw new Unopened();
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
            spec.started().accept(owner.seconds());
            boolean vod = !state.live() && state.seekable();
            // start-paused lets VLC play a few frames before it pauses: back to the top while it waits, or whoever
            // loads it first starts that far in.
            if (vod && startSeconds == 0 && go != null && !go.isDone()) {
                owner.seek(0);
            }
            // Paused until the server says to play, and from where: the top once every listener has a fresh track
            // loaded, so a slower download doesn't leave one behind; at once, from where it is, for one already
            // playing (see Streams#started). A live stream has no place in it to agree on.
            long goDeadline = System.nanoTime() + MediaKind.barrierTicks(spec.track()) * PlaybackClock.NANOS_PER_TICK
                    + 5_000_000_000L;
            while (go != null && !state.live() && !go.isDone() && System.nanoTime() < goDeadline) {
                checkCancelled(cancelled);
                sleep();
            }
            long now = System.nanoTime();
            Go told = go == null ? null : go.getNow(null);
            // Without the server's word (an announcement, or it never came): where the server was when it sent the
            // stream, plus the time it took to load here.
            // A moving disc's player opened where its clock was, and plays on from there (see Backend#start).
            long target = shared.disc != null ? shared.disc.millis() * 1_000_000
                    : told != null ? told.position(now)
                    : startSeconds > 0 ? Math.round(startSeconds * 1e9) + Math.max(0, now - requestedAt) : 0;
            if (vod && target >= SYNC_TOLERANCE_NANOS && shared.disc == null) {
                owner.seek(target / 1_000_000);
            }
            owner.resume();
            if (shared.diagnostics != null) {
                shared.diagnostics.playing(told != null, target / 1e9);
            }
            // Where the track should be from now on, by this computer's clock: what drift is measured against.
            shared.id = shared.id.at(now - (vod ? target : 0));
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
                owner.failed(source);
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

    /**
     * One playback on a native player here, and the streams playing it; released when the last of them closes. What
     * shows or measures it (a display, a hi-fi stack, a remote, the stage lights) looks it up once (see
     * {@link #playback}) and asks it.
     */
    public static final class Playback {
        /** Its display: the Blu-ray player whose video it is (null for none: a record's audio alone). */
        @Nullable
        final BlockPos display;
        final Backend owner;
        @Nullable
        final StreamSpec.DiscClock disc;
        /** How closely it is held to where it should be (see correct). */
        final Hold held;
        /** What its video's playback took (see Diagnostics); null for a record's. */
        @Nullable
        final Diagnostics diagnostics;
        /** Done once it plays; failed if it never will. */
        final CompletableFuture<Void> playing = new CompletableFuture<>();
        /**
         * Which playback it is; its origin, the track's top (System.nanoTime), is where the server said as it started
         * playing here, and what drift is measured against.
         */
        volatile PlaybackId id;
        volatile boolean live;
        volatile boolean seekable;
        private int users = 1;
        private int tapped;
        /** When the next drift check is due (see correct); guarded by this. */
        private long nextCheck;
        /** When it last sought; on the sound thread (see correct). */
        private long seekedAt;

        Playback(PlaybackId id, @Nullable BlockPos display, Backend owner, @Nullable StreamSpec.DiscClock disc) {
            this.id = id;
            this.display = display;
            this.owner = owner;
            this.disc = disc;
            held = disc == null ? TRACK_HOLD : DISC_HOLD;
            diagnostics = owner.diagnostics();
        }

        /** Where it is shown, or else played from: its Blu-ray player or hi-fi stack if a video, else its source. */
        @Nullable
        public BlockPos at() {
            return display != null ? display : id.source();
        }

        /** Whether it plays here yet: loaded, and lined up with the others. */
        public boolean plays() {
            return playing.isDone() && !playing.isCompletedExceptionally();
        }

        boolean failed() {
            return playing.isCompletedExceptionally();
        }

        /** Where it plays here, by this computer's clock (see Progress). */
        public Progress progress() {
            if (!plays()) {
                return new Progress(true, 0, 0);
            }
            double length = live ? 0 : owner.seconds();
            double seconds = Math.max(0, (System.nanoTime() - id.origin()) / 1e9);
            return new Progress(false, length > 0 ? Math.min(seconds, length) : seconds, length);
        }

        /** Where its player actually is, rather than where it should be: for the development probes. */
        public Progress decoded() {
            return new Progress(!plays(), Math.max(0, owner.time()) / 1000.0, owner.seconds());
        }

        /** How many seconds it has left as it plays here, by this computer's clock; -1 if loading, live or unknown. */
        double remaining() {
            return plays() && !live && owner.seconds() > 0
                    ? owner.seconds() - (System.nanoTime() - id.origin()) / 1e9 : -1;
        }

        /**
         * Its video as it plays here (its displays show it); null for none, or not yet: loaded, it waits paused on its
         * first frame for the others, who would still see nothing.
         */
        @Nullable
        public VideoFrame frame() {
            return plays() ? owner.frame() : null;
        }

        /** What it sounds like here at this moment, after its tone (see Spectrum); null if it doesn't play yet. */
        @Nullable
        public Spectrum.Frame spectrum() {
            MediaTaps taps = taps();
            return taps == null ? null : taps.get(Spectrum.class, Spectrum::new).sample(taps.now());
        }

        /** Its taps (see MediaTaps); null for none, or while it doesn't play yet. */
        @Nullable
        public MediaTaps taps() {
            return plays() ? owner.taps() : null;
        }

        /** It plays through {@code tone}: of what shows or plays it (see Equalizer#set). */
        public void equalize(Tone tone) {
            owner.equalize(tone);
        }

        /** How far into its track its player is (ms); -1 if it can't tell. */
        public long time() {
            return owner.time();
        }

        /** Whether it is live with its media still coming in, as of {@code now}. */
        public boolean liveAndFlowing(long now) {
            return live && owner.recentMedia(now);
        }

        /**
         * Whether a stream of {@code joining} plays this (see PlaybackId#same); if so, it is a user. A live broadcast
         * has no place in it to tell playbacks apart by: every stream of it here plays the one.
         */
        synchronized boolean join(PlaybackId joining) {
            boolean same = live ? id.track().equals(joining.track()) && Objects.equals(id.disc(), joining.disc())
                    : id.same(joining);
            if (users == 0 || failed() || !same) {
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
         * be by now (from its {@link #id}'s origin, set where the server said as it started). Stalling to download (a
         * slow connection) leaves it behind: past its {@link #held} drift, it seeks to where it should be. Measured
         * by this computer's clock, never the server's, whose count falls behind whenever it runs below 20 TPS: every
         * client started where the server said, and clocks keep pace with each other.
         */
        void correct() {
            long now = System.nanoTime();
            synchronized (this) {
                if (now - nextCheck < 0 || !plays()) {
                    return;
                }
                nextCheck = now + CHECK_NANOS;
            }
            long at = owner.time();
            long expected = disc == null ? (now - id.origin()) / 1_000_000 : disc.millis();
            double length = owner.seconds();
            if (diagnostics != null && now - diagnostics.playing >= Diagnostics.REPORT_AFTER_NANOS) {
                diagnostics.send();
            }
            if (live || !seekable || at < 0 || length > 0 && expected > length * 1000 - END_MARGIN_MILLIS) {
                return;
            }
            boolean seek = Math.abs(expected - at) >= held.driftMillis() && now - seekedAt >= held.cooldownNanos();
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
            synchronized (PLAYBACKS) {
                PLAYBACKS.remove(this);
            }
            owner.release();
        }
    }

    static final class Owned extends CmsStream.Wrapper {
        private final Playback shared;
        private final AtomicBoolean closed = new AtomicBoolean();
        private long endedAt;
        /** It played to the end, or broke off: closed for good, not just dropped by the sound engine. */
        private volatile boolean done;

        private Owned(Playback shared, AudioStream tap) {
            super(tap);
            this.shared = shared;
        }

        boolean done() {
            return done;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            if (closed.get()) {
                return ByteBuffer.allocateDirect(0);
            }
            Status state = shared.owner.status();
            if (state.ready()) {
                // Some HTTP inputs become seekable only after their first buffers have opened.
                shared.live = state.live();
                shared.seekable = state.seekable();
            }
            if (state.broken()) {
                done = true;
                close();
                throw new IOException("Native playback was interrupted");
            }
            if (state.ended()) {
                if (endedAt == 0) {
                    endedAt = System.nanoTime();
                } else if (System.nanoTime() - endedAt >= END_TAIL_NANOS && shared.owner.drained(source)) {
                    done = true;
                    close();
                    return ByteBuffer.allocateDirect(0);
                }
            } else {
                endedAt = 0;
            }
            shared.correct();
            return source.read(size);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // Still release the owned native player if the tap has already gone away.
                } finally {
                    shared.leave();
                }
            }
        }
    }
}
