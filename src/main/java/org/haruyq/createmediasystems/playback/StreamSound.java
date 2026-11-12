package org.haruyq.createmediasystems.playback;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.ApiHttp;
import org.haruyq.createmediasystems.ServerConfig;
import org.haruyq.createmediasystems.client.Reports;
import org.haruyq.createmediasystems.stream.PlaybackClock;
import org.haruyq.createmediasystems.stream.ProviderUrls;
import org.haruyq.createmediasystems.stream.StreamFeature;
import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Plays a {@link StreamSpec}: on the native player (see NativeAudio), or as the API's {@code /stream} response (raw
 * PCM s16le, 48 kHz, mono, or that as Ogg Vorbis; see API.md), streamed.
 */
public final class StreamSound extends AbstractTickableSoundInstance {
    // The engine queues 4 one-second buffers on start, from its own thread; have them ready.
    private static final int PREBUFFER_BYTES = Pcm.BYTES_PER_SECOND * 5;
    /** Silence handed to the engine while the download is behind (see PcmStream#read). */
    private static final int STARVED_SILENCE_BYTES = Pcm.BYTES_PER_SECOND / 4;
    /**
     * Ticks to fade from silence to full volume and back: a stream fades in as it starts and as its volume rises,
     * and out before it stops for the listener leaving (getting off a train), so nothing cuts in or off.
     */
    private static final int FADE_TICKS = 20;
    /** How often (ticks) a stream the engine had no channel for asks again, after the first tick (see waitingForChannel). */
    private static final int CHANNEL_RETRY_TICKS = 20;
    /**
     * What the server waits for its listeners to load a track (see Streams#MAX_LOAD_TICKS): the API may first wait out
     * YouTube's pre-roll ad before the audio exists (see API.md).
     */
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofNanos(Streams.MAX_LOAD_TICKS * PlaybackClock.NANOS_PER_TICK);
    // HTTP/1.1: through Cloudflare, an HTTP/2 stream read at playback speed is reset (RST_STREAM) after about
    // 3 minutes; HTTP/1.1 runs to the end. Resuming covers any other proxy or network drop.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final StreamSpec spec;
    /** When the server said to play: a stream joining mid-way catches up on its own start-up delay from here. */
    private final long requestedAt = System.nanoTime();
    private volatile double requestedSeconds = -1;
    /**
     * The server's word to play, which a source's stream waits for once loaded (see NativeAudio#open); null for one
     * that plays at once (an announcement, a portable emitter).
     */
    @Nullable
    private final CompletableFuture<NativeAudio.Go> go;
    private int ticksWithoutReason;
    private boolean seenPresent;
    private int ticksPlayed = Math.floorMod(System.identityHashCode(this), SoundPhysicsCompat.INTERVAL_TICKS);
    private final SoundPhysicsCompat soundPhysics = new SoundPhysicsCompat();
    /** Ticks since it was made, so one that keeps being dropped at once isn't started again every tick. */
    private int age;
    /** The engine asked for its audio, which it does only once it has a channel for it (see waitingForChannel). */
    private boolean requested;
    private int channelWaits;
    // Written on the main thread (release) and the IO pool (open); volatile so whichever runs second sees the other.
    private volatile boolean released;
    @Nullable
    private volatile PcmStream stream;
    /** The native player's audio (see NativeAudio). */
    @Nullable
    private volatile AudioStream direct;

    /**
     * Plays {@code spec} from where it says, heard from its emitter (see StreamSpec.Emitter). The emitter's grace for
     * being absent also covers the start: the server sends "play" right away but the jukebox's "has a disc" block
     * state only at the end of its tick, so at first the disc may not be there yet.
     */
    public StreamSound(StreamSpec spec) {
        super(StreamFeature.STREAM.get(), spec.category(), SoundInstance.createUnseededRandom());
        this.spec = spec;
        go = spec.source() == null ? null : new CompletableFuture<>();
        attenuation = Attenuation.NONE;
        relative = spec.emitter().position() == null;
        follow();
        volume = 0;
    }

    /** Starts even at the silent edge of the range; the volume rises as the listener walks in. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        StreamSpec.Emitter emitter = spec.emitter();
        if (emitter.pitch() != null) {
            // The very same music stream gets Doppler pitch, not an overlaid sound effect.
            double next = emitter.pitch().getAsDouble();
            if (Double.isFinite(next)) {
                pitch = DopplerMath.smooth(pitch, next);
            }
        }
        age++;
        boolean isPresent = emitter.present().getAsBoolean();
        boolean heard = isPresent && emitter.nearby().getAsBoolean();
        seenPresent |= isPresent;
        ticksWithoutReason = heard ? 0 : ticksWithoutReason + 1;
        if (seenPresent && !isPresent || ticksWithoutReason > FADE_TICKS) {
            stop();
        }
        double step = 1.0 / FADE_TICKS;
        volume = (float) Mth.clamp(heard ? emitter.gain().getAsDouble() : 0, volume - step, volume + step);
        follow();
        // Positional streams only: Sound Physics leaves sounds at the listener alone.
        if (!relative && SoundPhysicsCompat.available() && ticksPlayed++ % SoundPhysicsCompat.INTERVAL_TICKS == 0) {
            Vec3 at = new Vec3(x, y, z);
            Vec3 from = emitter.tracedFrom() == null ? null : emitter.tracedFrom().get();
            soundPhysics.update(this, from == null ? at : from, at);
        }
    }

    private boolean wanted() {
        return spec.emitter().present().getAsBoolean() && spec.emitter().nearby().getAsBoolean();
    }

    /**
     * Whether the sound engine dropped it while it was still to play: its native audio neither ended nor broke off,
     * nothing here stopped it, and its block is still there and in range. The engine queues only a little of a native
     * stream ahead (VlcAudio hands it 100 ms at a time, all VLC has ready), so the game stalling longer than that
     * (saving, loading chunks) ran its OpenAL source dry, and the engine took that for the end: speakers went silent
     * one by one as a long video played, and the stereo pair played lopsided.
     */
    boolean wantsRestart() {
        return !released && !isStopped() && age > FADE_TICKS && direct instanceof NativeAudio.Owned owned
                && !owned.done() && wanted();
    }

    /**
     * Whether it is still to play but the engine had no free streaming channel for it, and so dropped it unheard
     * (silently, outside a dev environment). The engine frees a stopped stream's channel only on its next tick, so
     * restarting many sources at once (a new track) briefly needs twice their streams, which may be more than there
     * are (see LibraryMixin): the newest of them were dropped, and as nothing played them again, their speakers stayed
     * silent until the listener left and came back.
     */
    boolean waitingForChannel() {
        return !requested && !released && !isStopped() && wanted();
    }

    /**
     * While {@link #waitingForChannel}, whether to ask again now: the tick after, when the restart's old channels are
     * free, then once a second, as each ask waits on the sound thread.
     */
    boolean retryChannelNow() {
        return channelWaits++ % CHANNEL_RETRY_TICKS == 0;
    }

    /**
     * The same stream again, from where it is now: it joins its playback's native player, which plays on, so it is
     * back in step with the others at once, at the volume it had. A moving disc's is where its own clock says.
     */
    StreamSound again() {
        double resumeAt = spec.disc() == null ? spec.startSeconds() + (System.nanoTime() - requestedAt) / 1e9
                : spec.disc().seconds().getAsDouble();
        StreamSound again = new StreamSound(spec.startingAt(resumeAt));
        again.volume = volume;
        again.pitch = pitch;
        return again;
    }

    /** The server says to play, from {@code seconds} in as of now (see NativeAudio.Go). */
    void sync(double seconds) {
        if (go != null) {
            go.complete(new NativeAudio.Go(seconds, System.nanoTime()));
        }
    }

    /** Where in its track its API stream is heard now (s); -1 if nothing of it is, or it plays natively. */
    public double audibleSeconds() {
        PcmStream current = stream;
        return current == null ? -1 : current.heardSeconds(System.nanoTime());
    }

    /** Where in its track the API was last asked to stream from (s); -1 before it was. */
    public double requestedSeconds() {
        return requestedSeconds;
    }

    @Nullable
    Streams.Video video() {
        return spec.video();
    }

    String track() {
        return spec.track();
    }

    /** Moves to where it plays from now (a block on a moving structure, a train). */
    private void follow() {
        if (spec.emitter().position() != null) {
            Vec3 at = spec.emitter().position().get();
            x = at.x;
            y = at.y;
            z = at.z;
        }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        requested = true;
        return CompletableFuture.supplyAsync(this::open, Util.nonCriticalIoPool()).thenApply(spec::decorate);
    }

    /**
     * Closes the download once the engine no longer plays this sound. Needed because a sound stopped before
     * its stream is attached never has the stream closed by the engine, which would leave the pump running.
     */
    void release() {
        released = true;
        close(stream);
        close(direct);
    }

    /**
     * Whether the native player plays it, else the API's stream. An extension's video plays through the API's relay,
     * so a video's sound is its player's like any other; an extension's audio alone streams from the API. So does a
     * moving disc's YouTube audio: held to a clock of its own, it would keep seeking VLC's coarse network timeline.
     */
    private boolean playsNatively() {
        if (spec.apiOnly()) {
            return false;
        }
        if (spec.video() != null) {
            return true;
        }
        URI track = uri(spec.track());
        return !usesApiAudio(spec.track()) && !(spec.disc() != null && track != null && ProviderUrls.youtube(track));
    }

    private AudioStream open() {
        if (playsNatively()) {
            try {
                String missing = Vlc.missing();
                if (missing != null) {
                    throw new IOException(missing);
                }
                AudioStream opened = NativeAudio.play(
                        () -> new VlcPlayer(spec.video(), requestedAt, spec.startSeconds()),
                        spec, requestedAt, () -> released, go);
                direct = opened;
                if (released) {
                    close(opened);
                }
                return opened;
            } catch (IOException | RuntimeException | LinkageError error) {
                if (!released) {
                    reportFailure(error instanceof IOException ? error.getMessage()
                            : "Native playback failed to initialize");
                    // Silent here, but no longer loading: the source and its other listeners don't wait on this one.
                    spec.started().accept(0);
                }
                stop();
                return new Silence();
            }
        }
        String api = ServerConfig.API_URL.get();
        // Download bytes may be shared; each reader and each physical emitter keeps its OWN media position.
        PcmStream opened = new PcmStream(Feed.read(spec.feedKey(api), spec.startSeconds(),
                this::request, reason -> {
                    LogUtils.getLogger().warn("Stream {} broke off: {}", spec.request(api, 0), reason);
                    ClientStreams.interrupted(this, reason);
                }), spec.source(), spec.channel(), spec.track(), spec.disc() != null);
        stream = opened;
        if (released) {
            close(opened);
        } else {
            opened.prebuffer();
            IOException failed = opened.failure();
            if (failed != null) {
                LogUtils.getLogger().warn("Could not stream {}", spec.request(api, 0), failed);
                reportFailure(ApiHttp.reason(failed));
                spec.started().accept(0);
            } else {
                lineUp(opened);
                spec.started().accept(0);
            }
        }
        return opened;
    }

    /**
     * Lines {@code opened} up to play. A moving disc's joins its own clock, start-up and download latency included.
     * Else, joining mid-way (start > 0): others are already listening, so land where they are now, not where the
     * server was when it said to play. A fresh start plays from the top for everyone: the server holds it at 0 until
     * a listener has it loaded (see Streams#started).
     */
    private void lineUp(PcmStream opened) {
        double startSeconds = spec.startSeconds();
        if (spec.disc() != null) {
            opened.lineUp(Math.max(0, spec.disc().seconds().getAsDouble() - startSeconds), null);
            return;
        }
        double catchUp = startSeconds > 0 ? (System.nanoTime() - requestedAt) / 1e9 : 0;
        SyncClock.Start start = SyncClock.start(spec.playback(requestedAt), startSeconds + catchUp);
        opened.lineUp(catchUp + start.skipSeconds(), start);
    }

    @Nullable
    private static URI uri(@Nullable String source) {
        try {
            return source == null ? null : URI.create(source);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Only the extension's relay carries media bytes through the API. */
    static boolean usesApiAudio(@Nullable String source) {
        URI uri = uri(source);
        return uri != null && ExtensionUrls.matches(uri);
    }

    /** Opens the API stream {@code offset} seconds in; fails with the API's reason on an error status. */
    private InputStream request(double offset) throws IOException {
        try {
            URI uri = URI.create(spec.request(ServerConfig.API_URL.get(), offset));
            requestedSeconds = Math.max(0, spec.startSeconds() + offset);
            HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream body = response.body()) {
                    throw new IOException(ApiHttp.errorDetail(response.statusCode(),
                            new String(body.readNBytes(4000), StandardCharsets.UTF_8)));
                }
            }
            boolean ogg = response.headers().firstValue("Content-Type").orElse("").startsWith("audio/ogg");
            return ogg ? decodeOgg(response.body()) : response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /**
     * The PCM of an Ogg Vorbis response ({@code format=ogg}), decoded by Minecraft's own Vorbis decoder as it
     * downloads, so everything after it still counts in PCM bytes. An empty body (a start at the end) stays empty.
     */
    static InputStream decodeOgg(InputStream body) throws IOException {
        BufferedInputStream ogg = new BufferedInputStream(body);
        ogg.mark(1);
        if (ogg.read() < 0) {
            ogg.close();
            return InputStream.nullInputStream();
        }
        ogg.reset();
        JOrbisAudioStream decoder;
        try {
            decoder = new JOrbisAudioStream(ogg);
        } catch (IOException | RuntimeException e) {
            ogg.close();
            throw e instanceof IOException io ? io : new IOException("Invalid Ogg stream", e);
        }
        AudioFormat format = decoder.getFormat();
        if (format.getChannels() != Pcm.FORMAT.getChannels() || format.getSampleRate() != Pcm.FORMAT.getSampleRate()) {
            decoder.close();
            throw new IOException("Unexpected Ogg format " + format);
        }
        return new InputStream() {
            private ByteBuffer chunk = ByteBuffer.allocate(0);

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                while (!chunk.hasRemaining()) {
                    try {
                        // 16-bit samples in native order, which is little-endian on every platform the game runs on.
                        chunk = decoder.read(16 * 1024);
                    } catch (RuntimeException e) { // the decoder's word for corrupt data
                        throw new IOException("Corrupt Ogg stream", e);
                    }
                    if (!chunk.hasRemaining()) {
                        return -1;
                    }
                }
                int n = Math.min(length, chunk.remaining());
                chunk.get(bytes, offset, n);
                return n;
            }

            @Override
            public void close() throws IOException {
                decoder.close();
            }
        };
    }

    private static void close(@Nullable AudioStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // closing a pipe or a tap can't meaningfully fail
        }
    }

    /** Silence, a tenth of a second at a time: the engine stops a stream that has nothing to give. */
    private static final class Silence implements CmsStream {
        @Override
        public AudioFormat getFormat() {
            return Pcm.FORMAT;
        }

        @Override
        public ByteBuffer read(int size) {
            return ByteBuffer.allocateDirect(Pcm.BYTES_PER_SECOND / 10);
        }

        @Override
        public void close() {
        }
    }

    /**
     * One stream's read of its {@link Feed}, whose pump thread reads ahead so the sound engine thread, which calls
     * {@link #read}, only waits on the network if the API falls behind real time.
     */
    static final class PcmStream implements CmsStream, MediaTaps.Relay {
        @Nullable
        private final String track;
        private final Feed.Reader feed;
        @Nullable
        private final BlockPos playback;
        private final Streams.Channel channel;
        /** A moving disc's: it plays to a clock that runs on while the game is paused (see CmsStream). */
        private final boolean followsClock;
        /** Its taps, offered what of it is heard as they ask (see heardUpTo), on System.nanoTime in µs. */
        private final MediaTaps taps = new MediaTaps(() -> heardUpTo(System.nanoTime()));
        /** Where in the track what it plays is heard, from its buffers' {@link Feed.Reader#mediaSeconds}. */
        final AudibleTimeline heard = new AudibleTimeline(Pcm.RATE);
        private long analyzedPosition = -1;
        private volatile boolean closed;
        /** Silence to play before the audio, to wait for the others of its playback (see lineUp). */
        private long silenceBytes;
        /** When to start, to line up with the others of its playback; null for at once. */
        @Nullable
        private SyncClock.Start start;

        PcmStream(Feed.Reader feed, @Nullable BlockPos playback, Streams.Channel channel, @Nullable String track,
                  boolean followsClock) {
            this.track = track;
            this.feed = feed;
            this.playback = playback == null ? null : playback.immutable();
            this.channel = channel;
            this.followsClock = followsClock;
            if (this.playback != null) {
                MediaTaps.open(this.playback, this);
            }
        }

        @Override
        public Streams.Channel channel() {
            return channel;
        }

        @Override
        @Nullable
        public String track() {
            return track;
        }

        @Override
        public MediaTaps taps() {
            return taps;
        }

        @Override
        public double heardSeconds(long now) {
            return closed ? -1 : heard.seconds(now);
        }

        /**
         * Offers its taps what of it is heard up to {@code now} (System.nanoTime), peeked from the feed without taking
         * it (silence as a tenth of a second of it); returns {@code now} in µs.
         */
        synchronized long heardUpTo(long now) {
            AudibleTimeline.Heard current = closed ? null : heard.at(now);
            if (current == null || current.seconds() < 0) {
                taps.clear();
                analyzedPosition = -1;
                if (current != null) {
                    taps.sound(new byte[Pcm.BYTES_PER_SECOND / 10], Pcm.RATE / 10, 1, now / 1000 - 100_000);
                }
                return now / 1000;
            }
            long top = feed.bytesAt(current.seconds());
            long position = feed.bytesAt(current.secondsAt(now));
            long from = analyzedPosition;
            if (from < 0 || position < from || position - from > Pcm.BYTES_PER_SECOND / 4) {
                taps.clear();
                from = Math.max(0, position - Pcm.BYTES_PER_SECOND / 5) & ~1L;
            }
            int length = (int) (position - from);
            if (length > 0) {
                byte[] pcm = feed.peek(from, length);
                if (pcm == null) {
                    taps.clear();
                    analyzedPosition = -1;
                    return now / 1000;
                }
                long at = current.at() + (from - top) * 1_000_000_000L / Pcm.BYTES_PER_SECOND;
                taps.sound(pcm, pcm.length / 2, 1, at / 1000);
                analyzedPosition = position;
            }
            return now / 1000;
        }

        /** Waits until the start of the track is buffered; returns early if closed or ended. */
        void prebuffer() {
            feed.prebuffer(PREBUFFER_BYTES, System.currentTimeMillis() + 30_000);
        }

        @Nullable
        IOException failure() {
            return feed.failure();
        }

        /**
         * Before playback: skips {@code seconds} of audio, waiting on the download if need be, or plays that much
         * silence first if negative; then starts as {@code start} says.
         */
        void lineUp(double seconds, @Nullable SyncClock.Start start) {
            this.start = start;
            long bytes = Pcm.bytes(seconds);
            if (bytes > 0) {
                feed.skip(bytes);
            } else {
                silenceBytes = -bytes;
            }
        }

        @Override
        public synchronized long attach(int source, long deviceNow) {
            long startAt = start == null ? 0 : start.playAt(deviceNow);
            heard.attach(source, startAt);
            taps.clear();
            analyzedPosition = -1;
            return startAt;
        }

        @Override
        public boolean advancesWhilePaused() {
            return followsClock;
        }

        @Override
        public AudioFormat getFormat() {
            return Pcm.FORMAT;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            if (closed) {
                // A stream stopped before the engine attached it still gets read once; that's the end, not an error.
                return ByteBuffer.allocateDirect(0);
            }
            int wanted = size & ~1;
            if (silenceBytes > 0) {
                int n = (int) Math.min(wanted, silenceBytes);
                silenceBytes -= n;
                return buffer(new byte[n], -1);
            }
            byte[] pcm;
            long position = feed.position();
            int ready = feed.available() & ~1;
            if (ready > 0 || feed.done()) {
                pcm = feed.read(ready > 0 ? Math.min(wanted, ready) : wanted);
            } else {
                // The download is behind. Never wait for it here: this is the sound engine's one thread, which
                // every sound and the game itself wait on, so a stalled connection froze the whole client.
                // Play a moment of silence instead.
                pcm = new byte[Math.min(wanted, STARVED_SILENCE_BYTES)];
                position = -1;
            }
            return buffer(pcm, position);
        }

        /** {@code pcm}, from {@code position} in the feed (-1: silence), handed to the engine. */
        private ByteBuffer buffer(byte[] pcm, long position) {
            heard.queue(position < 0 ? -1 : feed.mediaSeconds(position), pcm.length / 2);
            return ByteBuffer.allocateDirect(pcm.length).put(pcm).flip();
        }

        @Override
        public void close() {
            closed = true;
            heard.close();
            if (playback != null) {
                MediaTaps.close(playback, this);
            }
            feed.close();
        }
    }

    /** A stream couldn't start: shown above the hotbar. */
    private static void reportFailure(String reason) {
        // The overlay is gone in seconds; a stalled playlist is looked into from the log.
        LogUtils.getLogger().warn("CMS stream failed: {}", reason);
        Reports.show(Component.translatable("create_media_systems.stream.failed", reason), true);
    }
}
