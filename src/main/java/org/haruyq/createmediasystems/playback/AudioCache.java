package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.haruyq.createmediasystems.client.ClientConfig;
import org.haruyq.createmediasystems.stream.ProviderUrls;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Records' audio (YouTube and the like) kept on this computer for a while after it last played, so playing it again
 * needs neither the API nor the origin. The first play streams from the origin as before while a copy downloads
 * beside it; later plays open the copy. Each file's modified time is when it last played.
 */
final class AudioCache {
    /** Ranged, as yt-dlp downloads: YouTube slows one long request down to real time. */
    private static final long CHUNK_BYTES = 10L << 20;
    private static final Pattern TOTAL = Pattern.compile("/(\\d+)$");
    private static AudioCache shared;

    private final Path dir;
    private final LongSupplier maxAgeMillis;
    private final LongSupplier maxBytes;
    private final Set<String> downloading = ConcurrentHashMap.newKeySet();

    AudioCache(Path dir, LongSupplier maxAgeMillis, LongSupplier maxBytes) {
        this.dir = dir;
        this.maxAgeMillis = maxAgeMillis;
        this.maxBytes = maxBytes;
    }

    /** The game's cache, sized by the client config. */
    static synchronized AudioCache get() {
        if (shared == null) {
            shared = new AudioCache(FMLPaths.GAMEDIR.get().resolve("create_media_systems-cache"),
                    () -> Duration.ofDays(ClientConfig.AUDIO_CACHE_DAYS.get()).toMillis(),
                    () -> ClientConfig.AUDIO_CACHE_MEGABYTES.get() * (1L << 20));
        }
        return shared;
    }

    /** The kept audio of {@code source}, now counted as played; null if none is kept (or it is too old). */
    @Nullable
    URI lookup(URI source) {
        source = ProviderUrls.canonical(source);
        Path file = file(source);
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            if (age > maxAgeMillis.getAsLong() || maxBytes.getAsLong() <= 0) {
                Files.deleteIfExists(file);
                return null;
            }
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            return file.toUri();
        } catch (IOException failed) {
            return null; // played from the origin, as without a cache
        }
    }

    /** Downloads {@code media}, the whole audio of {@code source}, in the background to keep it; once at a time. */
    void fill(URI source, URI media, Map<String, String> headers) {
        source = ProviderUrls.canonical(source);
        String name = name(source);
        if (maxAgeMillis.getAsLong() <= 0 || maxBytes.getAsLong() <= 0 || !downloading.add(name)) {
            return;
        }
        Thread.ofVirtual().name("CMS audio cache").start(() -> {
            try {
                download(media, headers, name);
                prune();
            } catch (IOException | RuntimeException failed) {
                // The origin's error text can hold the signed URL; the play itself goes on from the origin.
                LogUtils.getLogger().info("Could not keep a record's audio: {}", failed.getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                downloading.remove(name);
            }
        });
    }

    /** Throws away the kept audio of {@code source}, which would not play. */
    void forget(URI source) {
        source = ProviderUrls.canonical(source);
        try {
            Files.deleteIfExists(file(source));
        } catch (IOException ignored) {
            // it goes with the next prune
        }
    }

    void download(URI media, Map<String, String> headers, String name) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path part = dir.resolve(name + ".part");
        long have = 0;
        try {
            try (OutputStream out = Files.newOutputStream(part)) {
                while (true) {
                    HttpRequest.Builder request = HttpRequest.newBuilder(media).timeout(Duration.ofSeconds(60))
                            .header("Range", "bytes=" + have + "-" + (have + CHUNK_BYTES - 1));
                    headers.forEach(request::header);
                    HttpResponse<InputStream> response = NativeMediaResolver.HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    int status = response.statusCode();
                    long got = 0;
                    try (InputStream in = response.body()) {
                        if (status != 200 && status != 206) {
                            throw new IOException("HTTP " + status);
                        }
                        byte[] buffer = new byte[64 * 1024];
                        for (int n; (n = in.read(buffer)) > 0; got += n) {
                            if (have + got + n > maxBytes.getAsLong()) {
                                throw new IOException("Larger than the whole cache");
                            }
                            out.write(buffer, 0, n);
                        }
                    }
                    have += got;
                    Matcher total = TOTAL.matcher(response.headers().firstValue("Content-Range").orElse(""));
                    if (status == 200 || got < CHUNK_BYTES || total.find() && have >= Long.parseLong(total.group(1))) {
                        break; // 200: the origin sent it whole, ignoring the range
                    }
                }
            }
            if (have == 0) {
                throw new IOException("Empty");
            }
            Files.move(part, dir.resolve(name + ".audio"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** Drops audio unplayed for too long, then the longest unplayed until the rest fits, and leftover partial files. */
    void prune() throws IOException {
        long now = System.currentTimeMillis();
        List<Path> kept = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".part") && !downloading.contains(fileName.substring(0, fileName.length() - 5))
                        || fileName.endsWith(".audio") && now - Files.getLastModifiedTime(file).toMillis() > maxAgeMillis.getAsLong()) {
                    Files.deleteIfExists(file);
                } else if (fileName.endsWith(".audio")) {
                    kept.add(file);
                }
            }
        }
        kept.sort(Comparator.comparingLong((Path file) -> modified(file)).reversed());
        long used = 0;
        for (Path file : kept) {
            used += Files.size(file);
            if (used > maxBytes.getAsLong()) {
                Files.deleteIfExists(file);
            }
        }
    }

    static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0;
        }
    }

    Path file(URI source) {
        return dir.resolve(name(source) + ".audio");
    }

    /** The file name for {@code source}: its hash, so no URL lands on disk. */
    static String name(URI source) {
        return sha256(source.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code bytes}' SHA-256, in hex. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
