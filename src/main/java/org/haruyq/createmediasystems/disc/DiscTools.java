package org.haruyq.createmediasystems.disc;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import com.sun.jna.Platform;

final class DiscTools {
    private static final String VERSION = "9.0.2";
    private static final String ROOT = "ffmpeg-" + VERSION + "-full_build-shared/";
    private static final URI DOWNLOAD = URI.create("https://github.com/GyanD/codexffmpeg/releases/download/"
            + VERSION + "/ffmpeg-" + VERSION + "-full_build-shared.zip");
    private static final String SHA256 = "8d31e162f1616e37aab3fa2db991b97e1b8dbeb1c8465fd81a81be16ff91b328";
    private static final int MAX_DOWNLOAD = 256 * 1024 * 1024;
    private static final int MAX_UNPACKED = 768 * 1024 * 1024;

    record Installed(String ffmpeg, String ffprobe) {}

    private DiscTools() {
    }

    static Installed ensure(Path directory, String ffmpeg, String ffprobe, Runnable downloading) throws IOException {
        boolean autoFfmpeg = automatic(ffmpeg, "ffmpeg"), autoFfprobe = automatic(ffprobe, "ffprobe");
        if (!autoFfmpeg && !autoFfprobe) {
            return new Installed(ffmpeg, ffprobe);
        }
        if (!Platform.isWindows() || !Platform.is64Bit() || Platform.isARM()) {
            throw new IOException("Automatic FFmpeg setup needs Windows x64; set discFfmpegPath and discFfprobePath to your own installation");
        }
        Path installed = install(directory, DOWNLOAD, SHA256, downloading);
        return new Installed(autoFfmpeg ? installed.resolve("ffmpeg.exe").toString() : ffmpeg,
                autoFfprobe ? installed.resolve("ffprobe.exe").toString() : ffprobe);
    }

    private static boolean automatic(String value, String name) {
        return value.isBlank() || value.equals(name);
    }

    static synchronized Path install(Path directory, URI download, String checksum, Runnable downloading) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(VERSION + "-win64");
        try (var channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (ready(target, checksum)) {
                return target;
            }
            downloading.run();
            Path staging = Files.createTempDirectory(directory, "ffmpeg-");
            try {
                Path archive = staging.resolve("download.part");
                download(download, archive, checksum);
                unpack(archive, staging, checksum);
                Files.delete(archive);
                remove(target);
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } finally {
                remove(staging);
            }
        }
    }

    private static boolean ready(Path directory, String checksum) {
        Path marker = directory.resolve(".complete");
        try {
            if (Files.size(marker) > 8192) {
                return false;
            }
            List<String> files = Files.readAllLines(marker);
            if (files.isEmpty() || !files.getFirst().equals(checksum)
                    || !Files.isRegularFile(directory.resolve("ffmpeg.exe")) || !Files.isRegularFile(directory.resolve("ffprobe.exe"))) {
                return false;
            }
            for (String line : files.subList(1, files.size())) {
                String[] parts = line.split("\t");
                if (parts.length != 2 || !parts[0].matches("[a-zA-Z0-9_.-]+")
                        || Files.size(directory.resolve(parts[0])) != Long.parseLong(parts[1])) {
                    return false;
                }
            }
            return files.size() > 2;
        } catch (IOException | NumberFormatException incomplete) {
            return false;
        }
    }

    private static void download(URI uri, Path target, String checksum) throws IOException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body(); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    if (response.statusCode() != 200 || response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_DOWNLOAD) {
                        throw new IOException("FFmpeg download failed (HTTP " + response.statusCode() + "); retry /cms_disc load");
                    }
                    var copy = worker.submit(() -> {
                        MessageDigest hash = digest();
                        try (var output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[64 * 1024];
                            long total = 0;
                            for (int read; (read = input.read(buffer)) != -1;) {
                                checkInterrupted();
                                if ((total += read) > MAX_DOWNLOAD) {
                                    throw new IOException("FFmpeg download exceeded its size limit");
                                }
                                hash.update(buffer, 0, read);
                                output.write(buffer, 0, read);
                            }
                        }
                        return HexFormat.of().formatHex(hash.digest());
                    });
                    if (!checksum.equals(copy.get(5, TimeUnit.MINUTES))) {
                        throw new IOException("FFmpeg download failed SHA-256 verification; retry /cms_disc load");
                    }
                } catch (java.util.concurrent.ExecutionException failed) {
                    throw new IOException("FFmpeg download failed; check your connection and retry /cms_disc load", failed.getCause());
                } catch (java.util.concurrent.TimeoutException timeout) {
                    throw new IOException("FFmpeg download timed out; retry /cms_disc load");
                } finally {
                    input.close();
                    worker.shutdownNow();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg setup was cancelled");
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void unpack(Path archive, Path directory, String checksum) throws IOException {
        List<String> files = new ArrayList<>(List.of(checksum));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            long total = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(ROOT)) {
                    continue;
                }
                name = name.substring(ROOT.length());
                if (!name.matches("bin/(ffmpeg\\.exe|ffprobe\\.exe|[a-zA-Z0-9_-]+\\.dll)|LICENSE|README\\.txt")) {
                    continue;
                }
                Path file = directory.resolve(name.substring(name.lastIndexOf('/') + 1));
                try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        checkInterrupted();
                        if ((total += read) > MAX_UNPACKED) {
                            throw new IOException("FFmpeg archive exceeded its size limit");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                files.add(file.getFileName() + "\t" + Files.size(file));
            }
        }
        for (String name : List.of("ffmpeg.exe", "ffprobe.exe", "LICENSE", "README.txt")) {
            if (!Files.isRegularFile(directory.resolve(name)) || Files.size(directory.resolve(name)) == 0) {
                throw new IOException("FFmpeg archive is incomplete; retry /cms_disc load");
            }
        }
        Files.write(directory.resolve(".complete"), files);
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("FFmpeg setup was cancelled");
        }
    }

    private static void remove(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
