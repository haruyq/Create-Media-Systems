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
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.client.Reports;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.UrlPolicy;

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
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Plays the API's {@code /stream} response (raw PCM s16le, 48 kHz, mono, or that as Ogg Vorbis; see API.md) as a
 * streamed sound.
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
    // HTTP/1.1: through Cloudflare, an HTTP/2 stream read at playback speed is reset (RST_STREAM) after about
    // 3 minutes; HTTP/1.1 runs to the end. Resuming covers any other proxy or network drop.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The API request for this stream, starting {@code offset} seconds into it. */
    private final DoubleFunction<String> urlAt;
    /** The track it plays, to line this stream up with others of the same playback. */
    private final String track;
    /** What plays it on the server (a source block), so streams of one source share a native player; null if unknown. */
    @Nullable
    private final BlockPos playback;
    /** Which load of its source's track it plays (see Streams.Load), to tell its playback by; 0 if unknown. */
    private final long load;
    private final double startSeconds;
    /** When the server said to play: a stream joining mid-way catches up on its own start-up delay from here. */
    private final long requestedAt = System.nanoTime();
    private final BooleanSupplier present;
    private final BooleanSupplier nearby;
    /** Individual moving CD pitch; default null leaves every other CMS stream unchanged. */
    @Nullable private DoubleSupplier dynamicPitch;
    @Nullable private NativeAudio.DiscClock discClock;
    @Nullable private java.util.function.IntSupplier bassMode;
    @Nullable private java.util.function.IntSupplier megaphoneDrive,megaphoneVolume;
    private boolean apiOnly;
    @Nullable private volatile AudibleAudioStream audibleClock;
    public double audibleSeconds(){var clock=audibleClock;return clock==null?-1:clock.seconds();}
    private volatile double requestedSeconds=-1;
    public double requestedSeconds(){return requestedSeconds;}
    private DoubleConsumer pcmMeter = value -> {};
    private final DoubleSupplier gain;
    /** The Blu-ray player whose video this is the sound of; null for none. */
    @Nullable
    private final Streams.Video video;
    private final Streams.Channel channel;
    /**
     * Told (from the loader thread) once the audio is loaded and lined up to play, with the track's length in seconds
     * as the native player sees it (0 if unknown or live).
     */
    private final DoubleConsumer started;
    /**
     * The server's word to play, which a block's stream waits for once loaded (see NativeAudio#open); null for one
     * that plays at once (an announcement).
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
    /** Where it plays from in the world now, asked every tick; null plays it at the listener. */
    @Nullable
    private final Supplier<Vec3> position;
    /** Where Sound Physics traces it from (see SoundPhysicsCompat); null for where it plays from. */
    @Nullable
    private final Supplier<Vec3> tracedFrom;
    // Written on the main thread (release) and the IO pool (open); volatile so whichever runs second sees the other.
    private volatile boolean released;
    @Nullable
    private volatile PcmStream stream;
    /** The native player's audio (see NativeAudio). */
    @Nullable
    private volatile AudioStream direct;

    /**
     * {@code position == null} plays it at the listener, not from anywhere. With {@code video}, its player also
     * plays the video that Blu-ray player's displays show. Volume follows
     * {@code gain} every tick (OpenAL's own falloff is off), fading to it rather than jumping. Stops at once when
     * {@code present} turns false after having been true (disc taken out, block gone), and once either has been false
     * for a second, fading out meanwhile; the grace also keeps edge jitter from cutting it. The grace also covers the start: the server sends "play" right away but the jukebox's
     * "has a disc" block state only at the end of its tick, so at first the disc may not be there yet.
     * {@code started} runs once the audio is loaded and about to play.
     */
    public StreamSound(DoubleFunction<String> urlAt, String track, @Nullable BlockPos playback, long load,
                double startSeconds, SoundSource source, @Nullable Supplier<Vec3> position,
                @Nullable Supplier<Vec3> tracedFrom, BooleanSupplier present,
                BooleanSupplier nearby, DoubleSupplier gain, @Nullable Streams.Video video, Streams.Channel channel,
                DoubleConsumer started) {
        super(CreateMediaSystems.STREAM.get(), source, SoundInstance.createUnseededRandom());
        this.urlAt = urlAt;
        this.track = track;
        this.playback = playback;
        this.load = load;
        this.startSeconds = startSeconds;
        this.present = present;
        this.nearby = nearby;
        this.gain = gain;
        this.video = video;
        this.channel = channel;
        this.started = started;
        go = playback == null ? null : new CompletableFuture<>();
        attenuation = Attenuation.NONE;
        this.position = position;
        this.tracedFrom = tracedFrom;
        relative = position == null;
        follow();
        volume = 0;
    }

    /** The very same music stream gets Doppler pitch, not an overlaid sound effect. */
    public void setDynamicPitch(DoubleSupplier source) { dynamicPitch = source; }

    /** Keep each fired CD's decoder and server playback position independent of other shots. */
    public void setDiscPlayback(UUID id, DoubleSupplier seconds) {
        discClock = new NativeAudio.DiscClock(id, seconds);
        // Moving YouTube audio must not repeatedly seek VLC's coarse network timeline.
        if(video==null)try{apiOnly|=org.haruyq.createmediasystems.stream.ProviderUrls.youtube(URI.create(track));}catch(IllegalArgumentException ignored){}
    }
    public void setBassMode(java.util.function.IntSupplier mode) { bassMode = mode; }
    public void setMegaphone(java.util.function.IntSupplier drive,java.util.function.IntSupplier volume){megaphoneDrive=drive;megaphoneVolume=volume;apiOnly=true;}
    public void setPcmMeter(DoubleConsumer observer) { pcmMeter = observer; }

    /** Starts even at the silent edge of the range; the volume rises as the listener walks in. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (dynamicPitch != null) {
            double next = dynamicPitch.getAsDouble();
            if (Double.isFinite(next)) {
                pitch = (float) Mth.clamp(pitch + .38 * (next - pitch), .65, 1.60);
            }
        }
        age++;
        boolean isPresent = present.getAsBoolean();
        boolean heard = isPresent && nearby.getAsBoolean();
        seenPresent |= isPresent;
        ticksWithoutReason = heard ? 0 : ticksWithoutReason + 1;
        if (seenPresent && !isPresent || ticksWithoutReason > FADE_TICKS) {
            stop();
        }
        double step = 1.0 / FADE_TICKS;
        volume = (float) Mth.clamp(heard ? gain.getAsDouble() : 0, volume - step, volume + step);
        follow();
        // Positional streams only: Sound Physics leaves sounds at the listener alone.
        if (!relative && SoundPhysicsCompat.available() && ticksPlayed++ % SoundPhysicsCompat.INTERVAL_TICKS == 0) {
            Vec3 at = new Vec3(x, y, z);
            Vec3 from = tracedFrom == null ? null : tracedFrom.get();
            soundPhysics.update(this, from == null ? at : from, at);
        }
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
                && !owned.done() && present.getAsBoolean() && nearby.getAsBoolean();
    }

    /**
     * Whether it is still to play but the engine had no free streaming channel for it, and so dropped it unheard
     * (silently, outside a dev environment). The engine frees a stopped stream's channel only on its next tick, so
     * restarting many sources at once (a new track) briefly needs twice their streams, which may be more than there
     * are (see LibraryMixin): the newest of them were dropped, and as nothing played them again, their speakers stayed
     * silent until the listener left and came back.
     */
    boolean waitingForChannel() {
        return !requested && !released && !isStopped() && present.getAsBoolean() && nearby.getAsBoolean();
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
     * back in step with the others at once, at the volume it had.
     */
    StreamSound again() {
        double playedSeconds = (System.nanoTime() - requestedAt) / 1e9;
        double resumeAt = discClock == null ? startSeconds + playedSeconds : discClock.seconds().getAsDouble();
        StreamSound again = new StreamSound(StreamRequestTimeline.resume(urlAt,startSeconds,resumeAt), track, playback, load, resumeAt, getSource(),
                position, tracedFrom, present, nearby, gain, video, channel, started);
        again.volume = volume;
        again.pitch = pitch;
        again.dynamicPitch = dynamicPitch;
        again.discClock = discClock;
        again.bassMode = bassMode;
        again.megaphoneDrive=megaphoneDrive;again.megaphoneVolume=megaphoneVolume;again.apiOnly=apiOnly;
        again.pcmMeter = pcmMeter;
        return again;
    }

    /** The server says to play, from {@code seconds} in as of now (see NativeAudio.Go). */
    void sync(double seconds) {
        if (go != null) {
            go.complete(new NativeAudio.Go(seconds, System.nanoTime()));
        }
    }

    long position(BlockPos source, String url) {
        PcmStream current = stream;
        long millis = current == null || !source.equals(playback) || !url.equals(track) ? -1 : current.position();
        return millis < 0 ? -1 : millis + Math.round(startSeconds * 1000);
    }

    @Nullable
    Streams.Video video() {
        return video;
    }

    String track() {
        return track;
    }

    /** Moves to where it plays from now (a block on a moving structure, a train). */
    private void follow() {
        if (position != null) {
            Vec3 at = position.get();
            x = at.x;
            y = at.y;
            z = at.z;
        }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        requested = true;
        return CompletableFuture.supplyAsync(this::open, Util.nonCriticalIoPool())
                .thenApply(opened -> {
                    if(opened instanceof PcmStream pcm && discClock!=null) {
                        var clock=new AudibleAudioStream(opened,startSeconds,pcm::lastReadSeconds);audibleClock=clock;opened=clock;
                    }
                    if(megaphoneDrive!=null) {
                        return new MegaphoneAudioStream(opened,megaphoneDrive,megaphoneVolume);
                    }
                    return bassMode == null ? opened : new BassAudioStream(opened,bassMode,pcmMeter);
                });
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

    private AudioStream open() {
        // An extension's video plays through the API's relay, so a video's sound is its player's like any other.
        if (!apiOnly && (video != null || !usesApiAudio(track))) {
            try {
                AudioStream opened = NativeAudio.open(URI.create(track), playback, load, video, startSeconds,
                        requestedAt, () -> released, channel, go, started, discClock);
                direct = opened;
                if (released) {
                    close(opened);
                }
                return opened;
            } catch (IOException | RuntimeException | LinkageError error) {
                if (!released) {
                    reportFailure(error instanceof IOException ? error.getMessage()
                            : "Native playback failed to initialize");
                }
                stop();
                return new Silence();
            }
        }
        // Keyed by the request from the top of the track, which is the same for every stream of this audio whatever
        // its start: the speakers of one source share one download (see Feed).
        String feedKey = urlAt.apply(-startSeconds);
        // Download bytes may be shared; each reader and each physical emitter keeps its OWN media position.
        PcmStream opened = new PcmStream(Feed.read(feedKey, startSeconds,
                this::request, reason -> {
                    LogUtils.getLogger().warn("Stream {} broke off: {}", urlAt.apply(0), reason);
                    ClientStreams.interrupted(this, reason);
                }), playback, channel, track);
        stream = opened;
        if (released) {
            close(opened);
        } else {
            opened.prebuffer();
            IOException failed = opened.failure();
            if (failed != null) {
                LogUtils.getLogger().warn("Could not stream {}", urlAt.apply(0), failed);
                reportFailure(UrlPolicy.reason(failed));
            } else {
                // Joining mid-way (start > 0): others are already listening, so land where they are now, not where
                // the server was when it said to play. A fresh start plays from the top for everyone: the server
                // holds it at 0 until a listener has it loaded (see Streams#started).
                if(discClock!=null) {
                    // Each listener joins this physical emitter's own clock, including startup/download latency.
                    opened.lineUp(Math.max(0,discClock.seconds().getAsDouble()-startSeconds),null);
                } else {
                    double catchUp = startSeconds > 0 ? (System.nanoTime() - requestedAt) / 1e9 : 0;
                    SyncClock.Start start = SyncClock.start(playback, track, startSeconds + catchUp,
                            requestedAt - Math.round(startSeconds * 1e9), load);
                    opened.lineUp(catchUp + start.skipSeconds(), start);
                }
                started.accept(0);
            }
        }
        return opened;
    }

    /** Only the extension's relay carries media bytes through the API. */
    static boolean usesApiAudio(@Nullable String source) {
        try {
            return source != null && ExtensionUrls.matches(URI.create(source));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Opens the API stream {@code offset} seconds in; fails with the API's reason on an error status. */
    private InputStream request(double offset) throws IOException {
        try {
            // 90 s: the API may first wait out YouTube's pre-roll ad before the audio exists (see API.md).
            URI uri = URI.create(urlAt.apply(offset));
            requestedSeconds=StreamRequestTimeline.requestedPosition(uri);
            HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Streams.MAX_LOAD_TICKS * 50)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream body = response.body()) {
                    throw new IOException(UrlPolicy.errorDetail(response.statusCode(),
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
    private static final class Silence implements AudioStream {
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
    static final class PcmStream implements AudioStream, DeviceClock.Timed {
        final String track;
        private final Feed.Reader feed;
        @Nullable
        private final BlockPos playback;
        private final Streams.Channel channel;
        private final LightingMedia.Audio lighting = new LightingMedia.Audio();
        private final ArrayDeque<Played> played = new ArrayDeque<>();
        private int source;
        private long startAt;
        private long analyzedPosition = -1;
        private volatile boolean closed;
        private volatile double lastReadSeconds=-1;
        double lastReadSeconds(){return lastReadSeconds;}
        /** Silence to play before the audio, to wait for the others of its playback (see lineUp). */
        private long silenceBytes;
        /** When to start, to line up with the others of its playback; null for at once. */
        @Nullable
        private SyncClock.Start start;

        private record Played(long at, long position, int bytes) {
        }

        PcmStream(Feed.Reader feed, @Nullable BlockPos playback, Streams.Channel channel, @Nullable String track) {
            this.track = track;
            this.feed = feed;
            this.playback = playback == null ? null : playback.immutable();
            this.channel = channel;
            if (this.playback != null) {
                LightingMedia.openRelay(this.playback, this);
            }
        }

        Streams.Channel channel() {
            return channel;
        }

        synchronized long position() {
            long now = System.nanoTime();
            Played current = null;
            for (Played buffer : played) {
                if (buffer.at <= now) {
                    current = buffer;
                }
            }
            return closed || current == null || current.position < 0
                    || now - current.at >= (long) current.bytes * 1_000_000_000L / Pcm.BYTES_PER_SECOND ? -1
                    : current.position * 1000 / Pcm.BYTES_PER_SECOND + (now - current.at) / 1_000_000;
        }

        @Nullable
        LightingMedia.AudioFrame lighting() {
            return lighting(System.nanoTime());
        }

        @Nullable
        synchronized LightingMedia.AudioFrame lighting(long now) {
            Played current = null;
            for (Played buffer : played) {
                if (buffer.at <= now) {
                    current = buffer;
                }
            }
            if (closed || current == null || now - current.at >= (long) current.bytes * 1_000_000_000L / Pcm.BYTES_PER_SECOND) {
                return null;
            }
            if (current.position < 0) {
                lighting.clear();
                analyzedPosition = -1;
                return new LightingMedia.AudioFrame(now / 1000, 0, 0, 0, 0, 0, LightingMedia.DEFAULT_BPM);
            }
            long position = current.position + ((now - current.at) * Pcm.BYTES_PER_SECOND / 1_000_000_000L & ~1L);
            long from = analyzedPosition;
            if (from < 0 || position < from || position - from > Pcm.BYTES_PER_SECOND / 4) {
                lighting.clear();
                from = Math.max(0, position - Pcm.BYTES_PER_SECOND / 5) & ~1L;
            }
            int length = (int) (position - from);
            if (length > 0) {
                byte[] pcm = feed.peek(from, length);
                if (pcm == null) {
                    lighting.clear();
                    analyzedPosition = -1;
                    return null;
                }
                long at = current.at + (from - current.position) * 1_000_000_000L / Pcm.BYTES_PER_SECOND;
                lighting.offerMono(pcm, at / 1000);
                analyzedPosition = position;
            }
            return lighting.sample(now / 1000);
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
            this.source = source;
            startAt = start == null ? 0 : start.playAt(deviceNow);
            played.clear();
            lighting.clear();
            analyzedPosition = -1;
            return startAt;
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

        private synchronized ByteBuffer buffer(byte[] pcm, long position) {
            lastReadSeconds=position<0?-1:feed.mediaSeconds(position);
            if (playback != null && source != 0 && pcm.length > 0) {
                int queued = DeviceClock.queuedBuffers(source);
                long samples = 0;
                var iterator = played.descendingIterator();
                for (int i = 0; i < queued && iterator.hasNext(); i++) {
                    samples += iterator.next().bytes / 2;
                }
                queued(DeviceClock.presentationTime(source, startAt, samples), position, pcm.length);
            }
            return ByteBuffer.allocateDirect(pcm.length).put(pcm).flip();
        }

        synchronized void queued(long at, long position, int bytes) {
            played.addLast(new Played(at, position, bytes));
            while (played.size() > 16) {
                played.removeFirst();
            }
        }

        @Override
        public void close() {
            closed = true;
            if (playback != null) {
                LightingMedia.closeRelay(playback, this);
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
