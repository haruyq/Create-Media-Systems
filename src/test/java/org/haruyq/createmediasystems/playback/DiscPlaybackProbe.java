package org.haruyq.createmediasystems.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

public final class DiscPlaybackProbe {
    public interface Lib extends Vlc.Lib {
        void libvlc_release(Pointer instance);
    }

    private static final class Output {
        final VideoFrame frame = new VideoFrame();
        final VlcAudio audio = new VlcAudio();
        volatile long samples, gap, end;
        int height;
        final Vlc.AudioPlay play = (opaque, data, count, pts) -> {
            if (end > 0) {
                gap = Math.max(gap, pts - end);
            }
            end = pts + count * 1_000_000L / Pcm.RATE;
            samples += count;
            audio.play.invoke(opaque, data, count, pts);
        };
        final Vlc.AudioPts flush = (opaque, pts) -> {
            end = 0;
            audio.flush.invoke(opaque, pts);
        };

        void attach(Lib lib, Pointer player) {
            lib.libvlc_video_set_callbacks(player, frame.lock, frame.unlock, frame.display, null);
            lib.libvlc_video_set_format_callbacks(player, frame.setup, null);
            lib.libvlc_audio_set_callbacks(player, play, null, null, flush, null, null);
            lib.libvlc_audio_set_format(player, "S16N", Pcm.RATE, VlcAudio.CHANNELS);
        }

        void read() {
            var picture = frame.beginRead();
            if (picture != null) {
                try {
                    height = picture.height();
                    assertTrue(picture.pixels().size() >= (long) picture.width() * height * 4);
                } finally {
                    frame.endRead();
                }
            }
        }
    }

    public static void play(String directory, URI... uris) throws Exception {
        NativeLibrary.getInstance(Path.of(directory, "libvlccore.dll").toString());
        Lib lib = Native.load(Path.of(directory, "libvlc.dll").toString(), Lib.class);
        String[] args = {"--quiet", "--no-video-title-show"};
        Pointer instance = lib.libvlc_new(args.length, args);
        assertNotNull(instance);
        Pointer[] players = new Pointer[uris.length];
        Output[] outputs = new Output[uris.length];
        StringBuilder trace = new StringBuilder();
        try {
            for (int i = 0; i < uris.length; i++) {
                Pointer media = lib.libvlc_media_new_location(instance, uris[i].toString());
                try {
                    lib.libvlc_media_add_option(media, ":start-paused");
                    lib.libvlc_media_add_option(media, ":network-caching=2000");
                    players[i] = lib.libvlc_media_player_new_from_media(media);
                } finally {
                    lib.libvlc_media_release(media);
                }
                outputs[i] = new Output();
                outputs[i].attach(lib, players[i]);
                assertEquals(0, lib.libvlc_media_player_play(players[i]));
            }
            long deadline = System.nanoTime() + 30_000_000_000L;
            for (Pointer player : players) {
                while (lib.libvlc_media_player_get_state(player) != 4 && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertEquals(4, lib.libvlc_media_player_get_state(player));
                assertEquals(1, lib.libvlc_media_player_is_seekable(player));
                lib.libvlc_media_player_set_time(player, 8000);
            }
            for (Pointer player : players) {
                lib.libvlc_media_player_set_pause(player, 0);
            }
            long started = System.nanoTime(), next = started;
            while (System.nanoTime() - started < 18_000_000_000L) {
                for (Output output : outputs) {
                    output.read();
                }
                if (System.nanoTime() >= next) {
                    trace.append((System.nanoTime() - started) / 1_000_000);
                    for (int i = 0; i < players.length; i++) {
                        trace.append(" | ").append(lib.libvlc_media_player_get_time(players[i]))
                                .append(" ms, ").append(outputs[i].height).append("p, gap ")
                                .append(outputs[i].gap).append(" us, samples ").append(outputs[i].samples);
                    }
                    trace.append('\n');
                    next += 1_000_000_000L;
                }
                Thread.sleep(25);
            }
            long host = lib.libvlc_media_player_get_time(players[0]);
            long viewer = lib.libvlc_media_player_get_time(players[1]);
            trace.append("Final host/viewer ms: ").append(host).append('/').append(viewer).append('\n');
            assertTrue(host >= 20000 && viewer >= 20000, trace.toString());
            assertTrue(Math.abs(host - viewer) < 700, trace.toString());
            for (Output output : outputs) {
                assertTrue(output.height > 0 && output.height <= 512, trace.toString());
                assertTrue(output.samples > Pcm.RATE, trace.toString());
                assertTrue(output.gap < 100_000, trace.toString());
            }
        } finally {
            Files.writeString(Path.of("build", "disc-vlc-result.txt"), trace);
            for (Pointer player : players) {
                if (player != null) {
                    lib.libvlc_media_player_stop(player);
                    lib.libvlc_media_player_release(player);
                }
            }
            lib.libvlc_release(instance);
            for (Output output : outputs) {
                if (output != null) {
                    output.frame.freePictures();
                }
            }
        }
    }
}
