package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.haruyq.createmediasystems.client.ClientFiles;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * libvlc 3, bound with JNA (which Minecraft ships). On 64-bit Windows it is VideoLAN's own VLC build bundled in the
 * jar (see build.gradle), unpacked once into the game folder; elsewhere it is the computer's own VLC 3.
 */
final class Vlc {
    /** The VLC options every player shares; each media adds its own (see NativeMediaResolver.Prepared). */
    private static final String[] ARGS = {
            "--quiet", "--no-video-title-show", "--no-snapshot-preview", "--no-osd", "--no-stats",
            "--no-metadata-network-access", "--http-reconnect"};

    @Nullable
    static final Lib LIB;
    @Nullable
    static final Pointer INSTANCE;
    /** Why VLC isn't there, for the player; null if it is. */
    @Nullable
    static final String MISSING;

    static {
        Lib lib = null;
        Pointer instance = null;
        String missing = null;
        try {
            lib = load();
            instance = lib.libvlc_new(ARGS.length, ARGS);
            if (instance == null) {
                missing = "VLC could not start";
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("VLC could not be loaded; discs and displays can't play media", e);
            missing = Platform.isWindows() && Platform.is64Bit() ? "VLC could not be loaded"
                    : "Install VLC 3 (videolan.org) to play media";
        }
        LIB = missing == null ? lib : null;
        INSTANCE = missing == null ? instance : null;
        MISSING = missing;
    }

    private Vlc() {
    }

    /** Loads VLC now, off the thread that would first need it (it unpacks the first time). */
    static void preload() {
        Thread.ofPlatform().daemon().name("CMS VLC loader").start(() -> {
            if (MISSING == null) {
                LogUtils.getLogger().info("VLC is ready");
            }
        });
    }

    private static Lib load() throws IOException {
        Map<String, Object> options = Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name());
        if (Platform.isWindows() && Platform.is64Bit()) {
            Path dir = unpack();
            // libvlc.dll needs libvlccore.dll; VLC finds its plugins next to it.
            NativeLibrary.getInstance(dir.resolve("libvlccore.dll").toString());
            return Native.load(dir.resolve("libvlc.dll").toString(), Lib.class, options);
        }
        if (Platform.isMac()) {
            NativeLibrary.addSearchPath("vlc", "/Applications/VLC.app/Contents/MacOS/lib");
            Path plugins = Path.of("/Applications/VLC.app/Contents/MacOS/plugins");
            if (Files.isDirectory(plugins)) {
                Native.load("c", LibC.class).setenv("VLC_PLUGIN_PATH", plugins.toString(), 1);
            }
        }
        return Native.load("vlc", Lib.class, options);
    }

    /** The bundled VLC, unpacked into {@code create_media_systems/vlc/<version>} unless it already is. */
    private static Path unpack() throws IOException {
        String version;
        try (InputStream in = resource("version")) {
            version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        Path dir = ClientFiles.DIR.resolve("vlc").resolve(version);
        Path done = dir.resolve(".unpacked");
        if (Files.exists(done)) {
            return dir;
        }
        LogUtils.getLogger().info("Unpacking VLC {} into {}", version, dir);
        try (ZipInputStream zip = new ZipInputStream(resource("win-x64.zip"))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                Path target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir)) {
                    throw new IOException("Bad entry in the bundled VLC: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(done, version);
        return dir;
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = Vlc.class.getResourceAsStream("/create_media_systems/vlc/" + name);
        if (in == null) {
            throw new IOException("This Create: Media Systems jar has no bundled VLC");
        }
        return in;
    }

    private interface LibC extends Library {
        int setenv(String name, String value, int overwrite);
    }

    /** The libvlc 3 calls CMS uses (libvlc.h, libvlc_media.h, libvlc_media_player.h). */
    interface Lib extends Library {
        /** libvlc_state_t */
        int PLAYING = 3, PAUSED = 4, STOPPED = 5, ENDED = 6, ERROR = 7;

        Pointer libvlc_new(int argc, String[] argv);

        /** Microseconds, on the clock VLC stamps audio with. */
        long libvlc_clock();

        Pointer libvlc_media_new_location(Pointer instance, String mrl);

        void libvlc_media_add_option(Pointer media, String option);

        void libvlc_media_release(Pointer media);

        Pointer libvlc_media_player_new_from_media(Pointer media);

        void libvlc_media_player_release(Pointer player);

        int libvlc_media_player_play(Pointer player);

        void libvlc_media_player_set_pause(Pointer player, int pause);

        void libvlc_media_player_stop(Pointer player);

        int libvlc_media_player_get_state(Pointer player);

        long libvlc_media_player_get_length(Pointer player);

        void libvlc_media_player_set_time(Pointer player, long millis);

        long libvlc_media_player_get_time(Pointer player);

        int libvlc_media_player_is_seekable(Pointer player);

        int libvlc_media_player_can_pause(Pointer player);

        void libvlc_audio_set_callbacks(Pointer player, AudioPlay play, @Nullable AudioPts pause,
                                        @Nullable AudioPts resume, AudioPts flush, @Nullable Callback drain,
                                        @Nullable Pointer opaque);

        void libvlc_audio_set_format(Pointer player, String format, int rate, int channels);

        void libvlc_video_set_callbacks(Pointer player, VideoLock lock, VideoUnlock unlock,
                                        @Nullable VideoDisplay display, @Nullable Pointer opaque);

        void libvlc_video_set_format_callbacks(Pointer player, VideoSetup setup, @Nullable Callback cleanup);

        /** The picture's own size; 0 if it has one. */
        int libvlc_video_get_size(Pointer player, int video, IntByReference width, IntByReference height);
    }

    interface AudioPlay extends Callback {
        void invoke(Pointer opaque, Pointer samples, int count, long pts);
    }

    interface AudioPts extends Callback {
        void invoke(Pointer opaque, long pts);
    }

    interface VideoLock extends Callback {
        Pointer invoke(Pointer opaque, Pointer planes);
    }

    interface VideoUnlock extends Callback {
        void invoke(Pointer opaque, Pointer picture, Pointer planes);
    }

    interface VideoDisplay extends Callback {
        void invoke(Pointer opaque, Pointer picture);
    }

    /** unsigned (*)(void **opaque, char *chroma, unsigned *width, unsigned *height, unsigned *pitches, unsigned *lines) */
    interface VideoSetup extends Callback {
        int invoke(Pointer opaque, Pointer chroma, Pointer width, Pointer height, Pointer pitches, Pointer lines);
    }
}
