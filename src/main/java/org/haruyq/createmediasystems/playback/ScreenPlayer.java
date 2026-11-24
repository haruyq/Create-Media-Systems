package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.util.function.DoubleSupplier;

import org.haruyq.createmediasystems.stream.DiscUrl;
import org.haruyq.createmediasystems.stream.Streams;
import org.haruyq.createmediasystems.stream.Tone;

import org.jetbrains.annotations.Nullable;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import net.minecraft.client.sounds.AudioStream;

/**
 * A player's shared screen (see DiscUrl#screen), played live as it comes over P2P: its pictures go to a display's
 * texture like a VLC player's (see VideoFrame), its sound to the streams that tap it (see VlcAudio), timed on VLC's
 * clock. Live only: nothing to seek, pause or wait for.
 */
public final class ScreenPlayer implements NativeAudio.Backend {
    /**
     * How long after it comes in a sample is heard. A stream reads 100 ms at a time about 300 ms ahead of what it plays
     * (see VlcAudio#read), plus the device's latency: at 450 ms the newest sample was only about 10 ms ahead of what a
     * read wanted, so any hitch left the rest of that read silent, at a different moment on each of a stereo pair's
     * streams, and the sound flicked between the left and right speakers. The host holds its video back as long, so
     * picture and sound play together.
     */
    public static final long SOUND_DELAY_MICROS = 700_000;
    /**
     * A sound this far off its run (the host restarting it, a stall longer than the margin above) starts a new one where
     * it now is due. Samples that come in late by less still play on time; a new run at each hitch of about 150 ms in
     * WebRTC handing the sound over (one every 30 s or so on a 1080p60 share) left that long a silence.
     */
    private static final long RESYNC_MICROS = 200_000;

    /** A share as this client gets it: from its host's P2P session, or the capture itself on the host. */
    public interface Feed {
        /** Either may be null: the host doesn't hear its own share twice. */
        void watch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound);

        void unwatch(@Nullable VideoTrackSink pictures, @Nullable AudioTrackSink sound);

        /** The share is over, or this client lost it. */
        boolean ended();
    }

    /** How a share's link opens here; set as the client starts (see ScreenClient). */
    public interface Opener {
        /** Fails with a reason safe to show the player. */
        Feed open(DiscUrl url) throws IOException;
    }

    private static volatile Opener opener;

    public static void opens(Opener opens) {
        opener = opens;
    }

    private final MediaTaps taps = new MediaTaps(() -> Vlc.LIB.libvlc_clock());
    private final VlcAudio audio = new VlcAudio(taps);
    @Nullable
    private final VideoFrame frame;
    private final VideoTrackSink pictures = this::picture;
    private final AudioTrackSink sound = this::sound;
    @Nullable
    private Feed feed;
    private volatile boolean released;
    /** When the next sample is heard (VLC's clock, µs); 0 before the first. WebRTC's audio thread. */
    private long nextPts;

    ScreenPlayer(@Nullable Streams.Video video) {
        frame = video == null ? null : new VideoFrame(taps);
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
    public void equalize(Tone tone) {
        audio.equalizer.set(tone.bands(), tone.width());
    }

    @Override
    public MediaTaps taps() {
        return taps;
    }

    @Override
    public void start(URI source, @Nullable DoubleSupplier opensAt) throws IOException {
        DiscUrl url = DiscUrl.parse(source.toString());
        Opener opens = opener;
        if (url == null || !url.screen() || opens == null) {
            throw new IOException("This client can't play screen shares");
        }
        Feed opened = opens.open(url);
        synchronized (this) {
            if (!released) {
                feed = opened;
                opened.watch(frame == null ? null : pictures, sound);
            }
        }
    }

    @Override
    public synchronized NativeAudio.Status status() {
        if (released) {
            return NativeAudio.Status.BROKEN;
        }
        return feed == null ? NativeAudio.Status.WAITING : new NativeAudio.Status(true, true, false, feed.ended(), false);
    }

    @Override
    public void seek(long millis) {
    }

    @Override
    public void resume() {
    }

    @Override
    public boolean recentMedia(long now) {
        return !released && (VlcPlayer.recent(audio.lastSamples, now, 2_000_000_000L)
                || frame != null && VlcPlayer.recent(frame.lastPicture, now, 2_000_000_000L));
    }

    @Override
    public void release() {
        synchronized (this) {
            if (released) {
                return;
            }
            released = true;
            if (feed != null) {
                feed.unwatch(pictures, sound);
            }
        }
        audio.clear();
        if (frame != null) {
            frame.close();
        }
    }

    private void picture(dev.onvoid.webrtc.media.video.VideoFrame picture) {
        if (frame != null) {
            frame.put(picture);
        }
    }

    private void sound(byte[] data, int bits, int rate, int channels, int frames) {
        if (bits != 16 || rate != Pcm.RATE || frames <= 0 || channels < 1 || channels > 2) {
            return;
        }
        byte[] stereo = new byte[frames * 4];
        if (channels == 2) {
            System.arraycopy(data, 0, stereo, 0, stereo.length);
        } else {
            for (int i = 0; i < frames; i++) {
                stereo[i * 4] = stereo[i * 4 + 2] = data[i * 2];
                stereo[i * 4 + 1] = stereo[i * 4 + 3] = data[i * 2 + 1];
            }
        }
        long due = Vlc.LIB.libvlc_clock() + SOUND_DELAY_MICROS;
        if (nextPts == 0 || Math.abs(nextPts - due) > RESYNC_MICROS) {
            nextPts = due;
        }
        audio.play(stereo, frames, nextPts);
        nextPts += frames * 1_000_000L / Pcm.RATE;
    }
}
