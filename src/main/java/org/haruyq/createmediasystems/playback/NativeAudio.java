package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.stream.MediaKind;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;

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
    /** What the server waits for its listeners to load a track (see Streams#MAX_LOAD_TICKS). */
    private static final long OPEN_TIMEOUT_NANOS = Streams.MAX_LOAD_TICKS * 50_000_000L;
    // Let the final four 100 ms OpenAL chunks finish before returning EOF.
    private static final long END_TAIL_NANOS = 500_000_000L;
    /** Longest a player waits, ready, for the streams joining it to tap it (see Shared#allTapped). */
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
    private static final Hold TRACK_HOLD = new Hold(3000, 10_000_000_000L);
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

    /** How far a playing track may drift off where it should be before it is sought there, at most once a cooldown. */
    private record Hold(long driftMillis, long cooldownNanos) {
    }

    /**
     * A moving disc's playback (see StreamSound#setDiscPlayback): its own identity, not the URL or a time tolerance,
     * and a server-owned clock its player opens at (see Backend#start) and is held to, closer than a track held to
     * where the server said it started: each listener rejoins a moving disc on its own after unloading, and a loading
     * stall must not leave one seconds behind.
     */
    record DiscClock(UUID id, DoubleSupplier seconds) {
        private static final Hold HOLD = new Hold(1000, 3_000_000_000L);

        /** Where its track is now (ms). */
        long millis() {
            return Math.max(0, Math.round(seconds.getAsDouble() * 1000));
        }
    }

    /**
     * {@code playback} is what plays it on the server, and {@code load} which load of its track (see
     * {@link Shared#join}); null and 0 if unknown. {@code display} is
     * the Blu-ray player whose video it is, if any. {@code loaded} is told the track's length in seconds (0 if
     * unknown or live) once it is loaded; with {@code go}, its player then waits, paused, for the server's word to
     * play, so every listener starts it together (see Streams#started).
     */
    static AudioStream open(URI source, @Nullable BlockPos playback, long load, @Nullable Streams.Video video,
                            double startSeconds, long requestedAt, BooleanSupplier cancelled, Streams.Channel channel,
                            @Nullable CompletableFuture<Go> go, DoubleConsumer loaded) throws IOException {
        return open(source, playback, load, video, startSeconds, requestedAt, cancelled, channel, go, loaded, null);
    }

    static AudioStream open(URI source, @Nullable BlockPos playback, long load, @Nullable Streams.Video video,
                            double startSeconds, long requestedAt, BooleanSupplier cancelled, Streams.Channel channel,
                            @Nullable CompletableFuture<Go> go, DoubleConsumer loaded, @Nullable DiscClock disc) throws IOException {
        if (Vlc.MISSING != null) {
            throw new IOException(Vlc.MISSING);
        }
        return retrying(() -> open(() -> new VlcPlayer(video, requestedAt, startSeconds), source, playback, load,
                video == null ? null : video.player(), startSeconds, requestedAt, cancelled, channel, go, loaded, disc),
                cancelled, RETRY_MILLIS);
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

    /**
     * How far into its video the Blu-ray player at {@code pos} plays here, by this computer's clock, and how long the
     * video is (0: unknown, or live); {@code loading} while it loads here, or waits for the others to.
     */
    public record Progress(boolean loading, double seconds, double length) {
    }

    /** Actual decoder position for this moving CD, for the development probe. */
    @Nullable
    public static Progress discProgress(UUID id) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if (!id.equals(shared.key.discId()) || shared.playing.isCompletedExceptionally()) continue;
                boolean loading = !shared.playing.isDone();
                return new Progress(loading, Math.max(0, shared.owner.time()) / 1000.0, shared.owner.seconds());
            }
        }
        return null;
    }

    /** The video of the Blu-ray player at {@code pos} as it plays here (see Progress); null if it plays none here. */
    @Nullable
    public static Progress progress(BlockPos pos) {
        return progress(shared -> pos.equals(shared.key.display()));
    }

    /** The same for {@code track} played by what is at {@code pos}, a video or not (a hi-fi stack's CD): for a remote. */
    @Nullable
    public static Progress progress(BlockPos pos, String track) {
        return progress(shared -> (pos.equals(shared.key.display()) || pos.equals(shared.playback))
                && shared.key.source().toString().equals(track));
    }

    @Nullable
    private static Progress progress(java.util.function.Predicate<Shared> of) {
        Progress progress = null;
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if (!of.test(shared) || shared.playing.isCompletedExceptionally()) {
                    continue;
                }
                if (!shared.playing.isDone()) {
                    progress = new Progress(true, 0, 0); // unless the one before still plays
                    continue;
                }
                double length = shared.live ? 0 : shared.owner.seconds();
                double seconds = Math.max(0, (System.nanoTime() - shared.origin) / 1e9);
                return new Progress(false, length > 0 ? Math.min(seconds, length) : seconds, length);
            }
        }
        return progress;
    }

    /**
     * The video of the Blu-ray player at {@code pos} as it plays here (its displays show it); null if it doesn't, or
     * not yet: loaded, it waits paused on its first frame for the others, who would still see nothing.
     */
    @Nullable
    public static VlcPlayer.Frame screen(BlockPos pos) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if (pos.equals(shared.key.display()) && shared.playing.isDone()
                        && !shared.playing.isCompletedExceptionally() && shared.owner instanceof VlcPlayer player) {
                    return player.frame;
                }
            }
        }
        return null;
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

    /**
     * Every playback here plays through the tone of what plays it: its Blu-ray player or hi-fi stack if it is a
     * video, else its source (see Equalizer#set).
     */
    public static void equalize(Function<BlockPos, Tone> tones) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                BlockPos at = shared.key.display() != null ? shared.key.display() : shared.playback;
                if (at != null && shared.owner instanceof VlcPlayer player) {
                    Tone tone = tones.apply(at);
                    player.audio.equalizer.set(tone.bands(), tone.width());
                }
            }
        }
    }

    /**
     * What the source at {@code pos} sounds like here at this moment, after its tone (see Spectrum); null if it
     * plays nothing here.
     */
    @Nullable
    public static Spectrum.Frame spectrum(BlockPos pos) {
        synchronized (SHARED) {
            for (Shared shared : SHARED.values()) {
                if ((pos.equals(shared.key.display()) || pos.equals(shared.playback)) && shared.playing.isDone()
                        && !shared.playing.isCompletedExceptionally() && shared.owner instanceof VlcPlayer player) {
                    return player.audio.spectrum();
                }
            }
        }
        return null;
    }

    /**
     * Joins the player of the same playback of {@code source} on this client if there is one, else opens one from
     * {@code players} (VLC's, or a test's stand-in).
     */
    static AudioStream open(Supplier<Backend> players, URI source, @Nullable BlockPos playback, long load,
                                    @Nullable BlockPos display, double startSeconds, long requestedAt,
                                    BooleanSupplier cancelled, Streams.Channel channel,
                                    @Nullable CompletableFuture<Go> go, DoubleConsumer loaded) throws IOException {
        return open(players, source, playback, load, display, startSeconds, requestedAt, cancelled, channel, go, loaded, null);
    }

    static AudioStream open(Supplier<Backend> players, URI source, @Nullable BlockPos playback, long load,
                                    @Nullable BlockPos display, double startSeconds, long requestedAt,
                                    BooleanSupplier cancelled, Streams.Channel channel,
                                    @Nullable CompletableFuture<Go> go, DoubleConsumer loaded, @Nullable DiscClock disc) throws IOException {
        long origin = requestedAt - Math.round(startSeconds * 1e9);
        Key key = new Key(source, display, disc == null ? null : disc.id());
        Shared shared;
        boolean opener = false;
        synchronized (SHARED) {
            shared = SHARED.get(key);
            if (shared == null || !shared.join(origin, playback, load, startSeconds > 0)) {
                shared = new Shared(key, playback, load, players.get(), origin, disc);
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
            return open(players, source, playback, load, display, startSeconds, requestedAt, cancelled, channel, go, loaded, disc);
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
            owner.start(shared.key.source(), shared.disc == null ? null : shared.disc.seconds());
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
            loaded.accept(owner.seconds());
            boolean vod = !state.live() && state.seekable();
            // start-paused lets VLC play a few frames before it pauses: back to the top while it waits, or whoever
            // loads it first starts that far in.
            if (vod && startSeconds == 0 && go != null && !go.isDone()) {
                owner.seek(0);
            }
            // Paused until the server says to play, and from where: the top once every listener has a fresh track
            // loaded, so a slower download doesn't leave one behind; at once, from where it is, for one already
            // playing (see Streams#started). A live stream has no place in it to agree on.
            long goDeadline = System.nanoTime() + MediaKind.barrierTicks(shared.key.source().toString()) * 50_000_000L
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
    private record Key(URI source, @Nullable BlockPos display, @Nullable UUID discId) {
    }

    /** A player and the streams playing it; released when the last of them closes. */
    private static final class Shared {
        final Key key;
        @Nullable
        final BlockPos playback;
        /** Which load of the track it plays (see Streams.Load); 0 if unknown. */
        final long load;
        final Backend owner;
        @Nullable
        final DiscClock disc;
        /** How closely it is held to where it should be (see correct). */
        final Hold held;
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

        Shared(Key key, @Nullable BlockPos playback, long load, Backend owner, long origin, @Nullable DiscClock disc) {
            this.key = key;
            this.playback = playback;
            this.load = load;
            this.owner = owner;
            this.disc = disc;
            held = disc == null ? TRACK_HOLD : DiscClock.HOLD;
            this.origin = origin;
            diagnostics = owner instanceof VlcPlayer player ? player.diagnostics : null;
        }

        /**
         * Whether a stream asked to play from the track's top at {@code origin} plays this; if so, it is a user.
         * Known on both sides, the server's {@code load} decides: the same load of the same source is this playback,
         * however long after the first stream the next one came, and another load (a restart) is new. While a track
         * loads every stream of it says 0 s, so by time alone, the side whose first speaker came 2 s later (walking
         * into its range, chunks loading in after a rejoin) opened a player of its own, and the stereo pair played
         * apart for the whole track. Without it: one joining the same source mid-way ({@code midway}) always does,
         * as the server's position for it lags the one we hear whenever the server falls below 20 TPS, and a fresh
         * start goes by time, so a restart is new.
         */
        synchronized boolean join(long origin, @Nullable BlockPos playback, long load, boolean midway) {
            if (users == 0 || playing.isCompletedExceptionally() || !samePlayback(origin, playback, load, midway)) {
                return false;
            }
            users++;
            return true;
        }

        private boolean samePlayback(long origin, @Nullable BlockPos playback, long load, boolean midway) {
            if (disc != null) return true; // Its UUID, not a timestamp tolerance or the URL, identifies this CD.
            boolean sameSource = playback != null && playback.equals(this.playback);
            if (load != 0 && this.load != 0) {
                return live || sameSource && load == this.load;
            }
            return midway && sameSource || live || Math.abs(origin - this.origin) / 1e9 < SyncClock.SAME_PLAYBACK_SECONDS;
        }

        synchronized void tapped() {
            tapped++;
        }

        /**
         * Every {@link #CHECK_NANOS}, from any of its streams' reads: how far its player is off where the track should
         * be by now ({@link #origin}, set where the server said as it started). Stalling to download (a slow
         * connection) leaves it behind: past its {@link #held} drift, it seeks to where it should be. Measured
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
            long expected = disc == null ? (now - origin) / 1_000_000 : disc.millis();
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
