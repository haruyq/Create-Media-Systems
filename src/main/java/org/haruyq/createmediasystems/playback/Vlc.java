package org.haruyq.createmediasystems.playback;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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
 * libvlc 3, bound with JNA (which Minecraft ships). On x86-64 Windows it is VideoLAN's own VLC build, downloaded once
 * into the game folder at startup (see {@link #unpack}); elsewhere it is the computer's own VLC 3.
 */
final class Vlc {
    /** The VLC options every player shares; each media adds its own (see NativeMediaResolver.Prepared). */
    private static final String[] ARGS = {
            "--quiet", "--no-video-title-show", "--no-snapshot-preview", "--no-osd", "--no-stats",
            "--no-metadata-network-access", "--http-reconnect"};
    /** Where CMS downloads VideoLAN's VLC; VideoLAN has no VLC 3 for Windows on ARM, so that uses the computer's own. */
    private static final boolean X64_WINDOWS = Platform.isWindows() && Platform.is64Bit() && !Platform.isARM();
    private static final String VERSION = "3.0.24";
    private static final String SHA256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323";

    /** Set once VLC is loaded; read {@link #missing} first. */
    @Nullable
    static volatile Lib LIB;
    @Nullable
    static volatile Pointer INSTANCE;
    @Nullable
    private static volatile String missing;
    /** Whether the download failed (offline at startup, say), so it is worth trying again; when it was last tried. */
    private static boolean retry;
    private static long triedAt;

    static {
        tryLoad();
    }

    private Vlc() {
    }

    /** A VLC instance of its own, with every player's options and {@code extra}; null without VLC, or if it won't start. */
    @Nullable
    static Pointer instance(String... extra) {
        String[] args = java.util.stream.Stream.concat(java.util.Arrays.stream(ARGS), java.util.Arrays.stream(extra))
                .toArray(String[]::new);
        return LIB == null ? null : LIB.libvlc_new(args.length, args);
    }

    /** Loads VLC: touching this class is what loads it, and it downloads the first time, so call it off the game's threads. */
    static void preload() {
    }

    /**
     * Why VLC isn't there, for the player; null if it is. A download that failed is tried again (on the caller's
     * thread, so not the game's), at most once a minute.
     */
    @Nullable
    static synchronized String missing() {
        if (LIB == null && retry && System.currentTimeMillis() - triedAt > 60_000) {
            tryLoad();
        }
        return missing;
    }

    private static synchronized void tryLoad() {
        triedAt = System.currentTimeMillis();
        retry = false;
        try {
            Lib lib = load();
            // The computer's own VLC may be VLC 4, whose calls differ from these.
            String version = lib.libvlc_get_version();
            if (version == null || !version.startsWith("3.")) {
                LogUtils.getLogger().warn("VLC {} is installed, not VLC 3", version);
                missing = "CMS needs VLC 3, not VLC " + version + " (videolan.org)";
                return;
            }
            Pointer instance = lib.libvlc_new(ARGS.length, ARGS);
            if (instance == null) {
                missing = "VLC could not start";
                return;
            }
            LIB = lib;
            INSTANCE = instance;
            missing = null;
            LogUtils.getLogger().info("VLC is ready");
        } catch (IOException e) {
            // Only the download throws this: a connection, an HTTP status or a checksum that may be right next time.
            LogUtils.getLogger().warn("VLC could not be downloaded; discs and displays can't play media until it is", e);
            retry = true;
            missing = "VLC could not be downloaded from videolan.org; check the internet connection";
        } catch (RuntimeException | LinkageError e) {
            LogUtils.getLogger().warn("VLC could not be loaded; discs and displays can't play media", e);
            missing = X64_WINDOWS ? "VLC could not be loaded" : "Install VLC 3 (videolan.org) to play media";
        }
    }

    private static Lib load() throws IOException {
        Map<String, Object> options = Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name());
        if (X64_WINDOWS) {
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

    /**
     * VideoLAN's own VLC for x86-64 Windows, downloaded once into {@code create_media_systems/vlc/<version>} and checked
     * against VideoLAN's published SHA-256; the parts playback and lighting analysis don't use (its interfaces,
     * scripting, network output) are left out.
     */
    private static Path unpack() throws IOException {
        Path dir = ClientFiles.DIR.resolve("vlc").resolve(VERSION);
        Path done = dir.resolve(".unpacked");
        if (Files.exists(done)) {
            removeOthers(dir);
            return dir;
        }
        String url = "https://download.videolan.org/pub/videolan/vlc/" + VERSION + "/win64/vlc-" + VERSION + "-win64.zip";
        LogUtils.getLogger().info("Downloading VLC {} into {}", VERSION, dir);
        byte[] bytes;
        try {
            HttpResponse<byte[]> response = NativeMediaResolver.HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            bytes = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (!SHA256.equalsIgnoreCase(AudioCache.sha256(bytes))) {
            throw new IOException("The VLC download does not match VideoLAN's SHA-256");
        }
        String root = "vlc-" + VERSION + "/";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.startsWith(root) || !Parts.kept(name.substring(root.length()))) {
                    continue;
                }
                Path target = dir.resolve(name.substring(root.length())).normalize();
                if (!target.startsWith(dir)) {
                    throw new IOException("Bad entry in the VLC download: " + name);
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(done, VERSION);
        removeOthers(dir);
        return dir;
    }

    /** Deletes the other VLCs CMS unpacked beside {@code dir} (older versions); any still in use stay. */
    private static void removeOthers(Path dir) {
        try (var others = Files.list(dir.getParent())) {
            for (Path other : others.filter(o -> !o.equals(dir) && Files.isRegularFile(o.resolve(".unpacked"))).toList()) {
                LogUtils.getLogger().info("Removing the old VLC in {}", other);
                try (var files = Files.walk(other)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException inUse) {
                    // Another game running it; next start tries again.
                }
            }
        } catch (IOException none) {
            // Nothing to remove.
        }
    }

    /** The parts of VLC CMS keeps (a class of its own, so checking them doesn't load VLC). */
    static final class Parts {
        private static final Set<String> LEFT_OUT = Set.of("gui", "visualization", "lua", "access_output", "mux",
                "services_discovery", "control", "video_splitter");
        private static final Set<String> STREAM_OUT = Set.of("libstream_out_smem_plugin.dll",
                "libstream_out_transcode_plugin.dll");

        /** Whether CMS keeps this file of VLC's (its path inside the VLC folder). */
        static boolean kept(String path) {
            if (path.equals("libvlc.dll") || path.equals("libvlccore.dll") || path.equals("COPYING.txt")) {
                return true;
            }
            if (!path.startsWith("plugins/")) {
                return false;
            }
            String[] parts = path.split("/");
            if (parts.length > 2 && parts[1].equals("stream_out")) {
                return STREAM_OUT.contains(parts[parts.length - 1]);
            }
            return parts.length <= 2 || !LEFT_OUT.contains(parts[1]);
        }
    }

    private interface LibC extends Library {
        int setenv(String name, String value, int overwrite);
    }

    /** The libvlc 3 calls CMS uses (libvlc.h, libvlc_media.h, libvlc_media_player.h). */
    interface Lib extends Library {
        /** libvlc_state_t */
        int PLAYING = 3, PAUSED = 4, STOPPED = 5, ENDED = 6, ERROR = 7;

        /** "3.0.24 Vetinari" and the like. */
        String libvlc_get_version();

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
