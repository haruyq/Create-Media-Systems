package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.ProviderUrls;

import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Displays' videos kept on this computer, so playing one again starts at once and needs no origin: up to the client
 * config's size, the longest unplayed going first. A video streams the first time it plays while yt-dlp downloads a
 * copy, one at a time, after the stream has had a head start; the next video of a disc playing here is fetched ahead
 * the same way (see ClientStreams#play). A copy is the video and its audio as two files, which VLC plays
 * together (no ffmpeg needed), and a {@code .meta} file, written last, naming them; its modified time is when the
 * video last played.
 */
final class VideoCache {
    /** How long a download waits, so it doesn't slow the stream it is a copy of while that starts. */
    private static final long HEAD_START_MILLIS = 10_000;
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 60 * 60;
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("CMS video cache").factory());
    private static VideoCache shared;

    private final Path dir;
    private final LongSupplier maxBytes;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    /** The downloads under way, by name (see Download). */
    private final Map<String, Download> downloading = new ConcurrentHashMap<>();

    VideoCache(Path dir, LongSupplier maxBytes) {
        this.dir = dir;
        this.maxBytes = maxBytes;
    }

    /** The game's cache, sized by the client config. */
    static synchronized VideoCache get() {
        if (shared == null) {
            shared = new VideoCache(FMLPaths.GAMEDIR.get().resolve("create_media_systems-video-cache"),
                    () -> ClientConfig.VIDEO_CACHE_MEGABYTES.get() * (1L << 20));
        }
        return shared;
    }

    /** The kept copy of {@code page}, at most {@code maxHeight} tall, now counted as played; null if there is none. */
    @Nullable
    NativeMediaResolver.Prepared lookup(URI page, int maxHeight) {
        page = ProviderUrls.canonical(page);
        Path meta = dir.resolve(name(page, maxHeight) + ".meta");
        try {
            JsonObject kept = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            Map<String, Path> files = files(kept);
            Path video = files.get("video");
            Path audio = files.get("audio");
            if (video == null) {
                throw new IOException("no video");
            }
            if (!Files.isRegularFile(video) || audio != null && !Files.isRegularFile(audio)) {
                Files.deleteIfExists(meta);
                return null;
            }
            Files.setLastModifiedTime(meta, FileTime.fromMillis(System.currentTimeMillis()));
            URI media = video.toUri();
            return NativeMediaResolver.Prepared.of(media, false, null, kept.get("duration").getAsDouble(),
                    audio == null ? List.of() : List.of(":input-slave=" + audio.toUri().toASCIIString()));
        } catch (IOException | RuntimeException unreadable) {
            return null; // played from the origin, as without a cache
        }
    }

    /** Whether a copy of {@code page} at most {@code maxHeight} tall is kept (not counted as played). */
    boolean has(URI page, int maxHeight) {
        page = ProviderUrls.canonical(page);
        return Files.exists(dir.resolve(name(page, maxHeight) + ".meta"));
    }

    /**
     * How much of the copy of {@code page} at most {@code maxHeight} tall is here, 0..1: all of it once kept, none
     * while it waits its turn; -1 if it is neither kept nor on its way (the cache is off, or yt-dlp couldn't keep it).
     */
    double progress(URI page, int maxHeight) {
        String name = name(ProviderUrls.canonical(page), maxHeight);
        if (Files.exists(dir.resolve(name + ".meta"))) {
            return 1;
        }
        Download download = downloading.get(name);
        return download != null ? download.fraction() : queued.contains(name) ? 0 : -1;
    }

    /**
     * Downloads {@code page}, at most {@code maxHeight} tall (0 for any), in the background to keep it, unless it is kept
     * or on its way already.
     */
    void fill(URI source, int maxHeight) {
        URI page = ProviderUrls.canonical(source);
        String name = name(page, maxHeight);
        if (maxBytes.getAsLong() <= 0 || Files.exists(dir.resolve(name + ".meta")) || !queued.add(name)) {
            return;
        }
        DOWNLOADS.execute(() -> {
            try {
                Thread.sleep(HEAD_START_MILLIS);
                download(page, name, maxHeight);
                prune();
            } catch (IOException | RuntimeException failed) {
                // yt-dlp's reason names the problem (a live stream, no plain format), never a signed URL.
                LogUtils.getLogger().info("Could not keep a video from {}: {}", page.getHost(), failed.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                downloading.remove(name);
                queued.remove(name);
            }
        });
    }

    /** Throws away the kept copies of {@code page}, which would not play. */
    void forget(URI page) {
        page = ProviderUrls.canonical(page);
        String prefix = prefix(page);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().startsWith(prefix) && file.toString().endsWith(".meta")) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException ignored) {
            // its files go with the next prune
        }
    }

    /** The files a copy's meta names, by kind ("video", and "audio" when kept apart); prune keeps exactly these. */
    private Map<String, Path> files(JsonObject meta) {
        Map<String, Path> files = new java.util.LinkedHashMap<>();
        for (String kind : List.of("video", "audio")) {
            if (meta.has(kind)) {
                files.put(kind, dir.resolve(meta.get(kind).getAsString()));
            }
        }
        return files;
    }

    /** The files' name for {@code page} at {@code maxHeight}: a DVD's copy is not a Blu-ray's. */
    static String name(URI page, int maxHeight) {
        return prefix(page) + maxHeight;
    }

    private static String prefix(URI page) {
        return AudioCache.name(page) + "-";
    }

    /**
     * Has yt-dlp download {@code page}'s video and audio (each a plain HTTPS file, so yt-dlp needs no ffmpeg to join
     * them; else one file with both), into a folder of its own, then moves them beside the others and writes the
     * meta file naming them.
     */
    private void download(URI page, String name, int maxHeight) throws IOException, InterruptedException {
        String height = YtDlp.height(maxHeight);
        Path part = dir.resolve(name + ".part");
        FileUtils.deleteQuietly(part.toFile());
        Files.createDirectories(part);
        try {
            String printed;
            try {
                printed = download(page, name, part, String.format(Locale.ROOT, YtDlp.SEPARATE_VIDEO, height) + ","
                        + YtDlp.SEPARATE_AUDIO);
            } catch (IOException noSeparateFiles) {
                printed = download(page, name, part, String.format(Locale.ROOT, YtDlp.SINGLE_FILE, height));
            }
            JsonObject meta = new JsonObject();
            for (String line : printed.split("\\R")) {
                String[] fields = line.split("\t", 3);
                if (fields.length < 3) {
                    continue;
                }
                Path file = Path.of(fields[2]);
                String kind = fields[0].equals("none") ? "audio" : "video";
                String kept = name + "." + kind + extension(file);
                Files.move(file, dir.resolve(kept), StandardCopyOption.REPLACE_EXISTING);
                meta.addProperty(kind, kept);
                if (!meta.has("duration")) {
                    meta.addProperty("duration", parseDuration(fields[1]));
                }
            }
            if (!meta.has("video")) {
                throw new IOException("yt-dlp downloaded no video");
            }
            writeMeta(name, meta);
        } finally {
            FileUtils.deleteQuietly(part.toFile());
        }
    }

    /** Keeps a copy of {@code video} (sound included) as {@code page}'s at most {@code maxHeight} tall, {@code seconds} long. */
    void keep(URI page, int maxHeight, Path video, double seconds) throws IOException {
        String name = name(ProviderUrls.canonical(page), maxHeight);
        Files.createDirectories(dir);
        String kept = name + ".video" + extension(video);
        Files.copy(video, dir.resolve(kept), StandardCopyOption.REPLACE_EXISTING);
        JsonObject meta = new JsonObject();
        meta.addProperty("video", kept);
        meta.addProperty("duration", seconds);
        writeMeta(name, meta);
    }

    /** The meta file goes last, whole at once: a copy counts as kept only once all of it is there. */
    private void writeMeta(String name, JsonObject meta) throws IOException {
        Path temp = dir.resolve(name + ".meta.part");
        Files.writeString(temp, meta.toString());
        Files.move(temp, dir.resolve(name + ".meta"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private String download(URI page, String name, Path into, String format) throws IOException, InterruptedException {
        Download progress = new Download();
        downloading.put(name, progress);
        return YtDlp.run(page, DOWNLOAD_TIMEOUT_SECONDS, progress::accept, "-f", format, "--match-filter", "!is_live",
                "--progress", "--newline", "--progress-template", "download:" + Download.TEMPLATE,
                "--max-filesize", Long.toString(maxBytes.getAsLong()), "--no-mtime",
                "-o", into.resolve("%(format_id)s.%(ext)s").toString(),
                "--print", "after_move:%(vcodec)s\t%(duration)s\t%(filepath)s");
    }

    /**
     * How far one yt-dlp run has got, by the progress lines {@link #TEMPLATE} has it print: bytes so far over bytes in
     * all, the files it fetches one after another (a video, then its audio) counted as each starts.
     */
    static final class Download {
        static final String MARK = "CMS-PROGRESS";
        /**
         * Whether the file is still downloading or finished, its bytes so far, and in all; NA while yt-dlp can't tell.
         * YouTube's files have no size up front: yt-dlp estimates it, at first as just what it has.
         */
        static final String TEMPLATE = MARK + " %(progress.status)s %(progress.downloaded_bytes)s"
                + " %(progress.total_bytes,progress.total_bytes_estimate)s";
        /** Bytes of the files done. */
        private double done;
        private volatile double fraction;

        /** One line yt-dlp printed; any but its progress is passed over. */
        synchronized void accept(String line) {
            String[] fields = line.strip().split(" ");
            if (fields.length != 4 || !fields[0].equals(MARK)) {
                return;
            }
            double now = bytes(fields[2]);
            if (fields[1].equals("finished")) {
                // Kept where it got to: the next file, unknown yet, would read as all done.
                done += Math.max(now, 0);
                return;
            }
            double of = bytes(fields[3]);
            if (now >= 0 && of > now) {
                fraction = (done + now) / (done + of);
            }
        }

        double fraction() {
            return fraction;
        }

        private static double bytes(String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException unknown) {
                return -1; // NA
            }
        }
    }

    static double parseDuration(String text) {
        try {
            return Math.max(0, Double.parseDouble(text));
        } catch (NumberFormatException unknown) {
            return 0; // VLC tells, from the file
        }
    }

    private static String extension(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * Drops the longest unplayed videos until the rest fit, files no meta names (an unfinished download), and the
     * folders of downloads no longer running.
     */
    void prune() throws IOException {
        List<Path> metas = new ArrayList<>();
        List<Path> others = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                (file.getFileName().toString().endsWith(".meta") ? metas : others).add(file);
            }
        }
        metas.sort(Comparator.comparingLong(AudioCache::modified).reversed());
        Set<String> named = new java.util.HashSet<>();
        long used = 0;
        for (Path meta : metas) {
            List<Path> parts = new ArrayList<>();
            try {
                parts.addAll(files(JsonParser.parseString(Files.readString(meta)).getAsJsonObject()).values());
            } catch (IOException | RuntimeException unreadable) {
                Files.deleteIfExists(meta);
                continue;
            }
            long size = 0;
            for (Path file : parts) {
                size += Files.exists(file) ? Files.size(file) : 0;
            }
            used += size;
            if (used > maxBytes.getAsLong()) {
                Files.deleteIfExists(meta);
                used -= size;
                continue;
            }
            parts.forEach(file -> named.add(file.getFileName().toString()));
        }
        for (Path file : others) {
            String fileName = file.getFileName().toString();
            String owner = fileName.substring(0, Math.max(0, fileName.indexOf('.')));
            if (!named.contains(fileName) && !queued.contains(owner)) {
                FileUtils.deleteQuietly(file.toFile());
            }
        }
    }
}
