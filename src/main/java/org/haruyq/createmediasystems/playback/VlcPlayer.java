package org.haruyq.createmediasystems.playback;

import static org.haruyq.createmediasystems.playback.Vlc.LIB;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;
import java.util.Locale;

import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import net.minecraft.client.sounds.AudioStream;

/**
 * One VLC player. Its audio goes to the streams that tap it (see VlcAudio); with video, its pictures go to a texture
 * a display shows (see VideoFrame). libvlc is only called under {@link #lock}, which {@link #release} holds while it stops
 * and frees the player on a thread of its own, so no call ever reaches a freed player; {@link #status} only tries
 * the lock, since the sound thread asks it on every read.
 */
public final class VlcPlayer implements NativeAudio.Backend {
    private static final ExecutorService RELEASER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("CMS VLC release").factory());

    private final ReentrantLock lock = new ReentrantLock();
    final VlcAudio audio = new VlcAudio();
    @Nullable
    final VideoFrame frame;
    /** Guarded by lock; null before start and once released. */
    @Nullable
    private Pointer player;
    private volatile boolean released;
    private volatile boolean resumed;
    private boolean live;
    /** The length the resolver gave (see NativeMediaResolver.Prepared#duration); 0 if VLC is to tell. */
    private double duration;
    /** See {@link #seconds}; set as status is asked. */
    private volatile double seconds;

    /** The tallest picture its video plays at (0 for any); unused without video. */
    private final int maxHeight;
    /** How far into its track it is asked to play (s), which a shared disc's stream starts at (see MediaSources). */
    private final double startSeconds;
    /** What its video's playback took, sent as it is released (see Diagnostics); null without video. */
    @Nullable
    final Diagnostics diagnostics;

    /**
     * A player of {@code video}, or of a record's audio alone if null, asked at {@code requestedAt} to play
     * {@code startSeconds} into its track.
     */
    VlcPlayer(@Nullable Streams.Video video, long requestedAt, double startSeconds) {
        this.startSeconds = startSeconds;
        frame = video == null ? null : new VideoFrame();
        var callbacks = new CallbackThreadInitializer(true, false, "CMS VLC callback");
        Native.setCallbackThreadInitializer(audio.play, callbacks);
        Native.setCallbackThreadInitializer(audio.flush, callbacks);
        if (frame != null) {
            Native.setCallbackThreadInitializer(frame.setup, callbacks);
            Native.setCallbackThreadInitializer(frame.lock, callbacks);
            Native.setCallbackThreadInitializer(frame.unlock, callbacks);
            Native.setCallbackThreadInitializer(frame.display, callbacks);
        }
        maxHeight = video == null ? 0 : MediaSources.height(video);
        diagnostics = video == null ? null : new Diagnostics(video, frame, audio, maxHeight, requestedAt);
    }

    @Override
    public AudioStream tap(Streams.Channel channel) {
        return audio.open(channel);
    }

    @Override
    public boolean drained(AudioStream tap) {
        return VlcAudio.drained(tap);
    }

    @Override
    @Nullable
    public VideoFrame frame() {
        return frame;
    }

    @Override
    @Nullable
    public Spectrum.Frame spectrum() {
        return audio.spectrum();
    }

    @Override
    public void equalize(Tone tone) {
        audio.equalizer.set(tone.bands(), tone.width());
    }

    @Override
    @Nullable
    public Diagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    @Nullable
    public LightingMedia.AudioFrame lightingAudio() {
        return audio.lighting();
    }

    @Override
    @Nullable
    public LightingMedia.Colors lightingColors() {
        return frame == null ? null : frame.lighting();
    }

    @Override
    public void start(URI source, @Nullable DoubleSupplier opensAt) throws IOException {
        if (diagnostics != null) {
            diagnostics.resolving(source);
        }
        NativeMediaResolver.Prepared media = MediaSources.resolve(source, frame != null, maxHeight, startSeconds);
        if (diagnostics != null) {
            diagnostics.resolved(media.live());
        }
        lock.lock();
        try {
            if (released) {
                return;
            }
            Pointer opened = LIB.libvlc_media_new_location(Vlc.INSTANCE, media.uri().toASCIIString());
            if (opened == null) {
                throw new IOException("The native player could not open this source");
            }
            try {
                for (String option : media.options()) {
                    LIB.libvlc_media_add_option(opened, option);
                }
                if (opensAt != null && !media.live()) {
                    // Seeking a newly paused input and immediately resuming can race VLC's asynchronous seek.
                    // Input start-time is applied before start-paused completes, so every CD opens at its server position.
                    LIB.libvlc_media_add_option(opened, String.format(Locale.ROOT, ":start-time=%.3f",
                            Math.max(0, opensAt.getAsDouble())));
                }
                // Paused once open, so it can seek to where the others are first (see NativeAudio#open).
                LIB.libvlc_media_add_option(opened, ":start-paused");
                if (frame == null) {
                    // A record neither fetches nor decodes a video track.
                    LIB.libvlc_media_add_option(opened, ":no-video");
                }
                player = LIB.libvlc_media_player_new_from_media(opened);
            } finally {
                LIB.libvlc_media_release(opened);
            }
            if (player == null) {
                throw new IOException("The native player could not open this source");
            }
            // Set before playing: VLC never briefly plays on the computer's own speakers.
            LIB.libvlc_audio_set_callbacks(player, audio.play, null, null, audio.flush, null, null);
            LIB.libvlc_audio_set_format(player, "S16N", Pcm.RATE, VlcAudio.CHANNELS);
            if (frame != null) {
                LIB.libvlc_video_set_format_callbacks(player, frame.setup, null);
                LIB.libvlc_video_set_callbacks(player, frame.lock, frame.unlock, frame.display, null);
            }
            live = media.live();
            duration = media.duration();
            LIB.libvlc_media_player_play(player);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void failed(URI source) {
        MediaSources.forget(source);
    }

    @Override
    public double seconds() {
        return seconds;
    }

    @Override
    public boolean recentMedia(long now) {
        return !released && (recent(audio.lastSamples, now, 2_000_000_000L)
                || frame != null && recent(frame.lastPicture, now, 2_000_000_000L));
    }

    static boolean recent(long at, long now, long lifetime) {
        return at != 0 && now - at >= 0 && now - at < lifetime;
    }

    @Override
    public NativeAudio.Status status() {
        if (!lock.tryLock()) {
            return NativeAudio.Status.WAITING;
        }
        try {
            if (player == null) {
                return NativeAudio.Status.BROKEN;
            }
            int state = LIB.libvlc_media_player_get_state(player);
            if (frame != null) {
                frame.measure(player);
            }
            boolean seekable = LIB.libvlc_media_player_is_seekable(player) != 0;
            // Opened paused (start-paused), unless it can't pause (some live streams), when it just plays. Playing
            // while it can pause is the moment before start-paused pauses it: not ready yet, or it would stay paused.
            boolean ready = state == Vlc.Lib.PAUSED
                    || state == Vlc.Lib.PLAYING && (resumed || LIB.libvlc_media_player_can_pause(player) == 0);
            long length = LIB.libvlc_media_player_get_length(player);
            // A video on demand of a known length is one, though HLS has no length until it plays a little.
            boolean isLive = duration <= 0 && NativeAudio.classifyLive(live, ready, length, seekable);
            seconds = duration > 0 ? duration : ready && !isLive ? length / 1000.0 : 0;
            return new NativeAudio.Status(ready, isLive, seekable,
                    state == Vlc.Lib.ENDED || resumed && state == Vlc.Lib.STOPPED, state == Vlc.Lib.ERROR);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void seek(long millis) {
        lock.lock();
        try {
            if (player != null) {
                audio.clearLighting();
                if (frame != null) {
                    frame.clearLighting();
                }
                LIB.libvlc_media_player_set_time(player, millis);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Never waits: asked from the sound thread (see NativeAudio.Playback#correct). */
    @Override
    public long time() {
        if (!lock.tryLock()) {
            return -1;
        }
        try {
            return player == null ? -1 : LIB.libvlc_media_player_get_time(player);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resume() {
        lock.lock();
        try {
            if (player != null) {
                LIB.libvlc_media_player_set_pause(player, 0);
                resumed = true;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release() {
        lock.lock();
        try {
            if (released) {
                return;
            }
            released = true;
        } finally {
            lock.unlock();
        }
        if (diagnostics != null) {
            diagnostics.send();
        }
        audio.clearLighting();
        if (frame != null) {
            frame.clearLighting();
        }
        // Stopping waits for VLC's threads, which may take a moment: never on the game's or the sound engine's.
        RELEASER.execute(() -> {
            lock.lock();
            try {
                if (player != null) {
                    LIB.libvlc_media_player_stop(player);
                    LIB.libvlc_media_player_release(player);
                    player = null;
                }
            } finally {
                lock.unlock();
            }
            audio.clearLighting();
            if (frame != null) {
                frame.close();
            }
        });
    }
}
