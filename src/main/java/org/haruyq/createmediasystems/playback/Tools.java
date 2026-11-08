package org.haruyq.createmediasystems.playback;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.haruyq.createmediasystems.client.ClientConfig;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import com.sun.jna.Platform;

/**
 * The programs displays resolve pages with, fetched from their GitHub releases into {@code create_media_systems/tools} and kept
 * up to date (a newer release is looked for once a day): yt-dlp, Deno (the JavaScript runtime yt-dlp needs to solve
 * YouTube's challenges) and a PO Token provider (bgutil, Rust build) with its yt-dlp plugin, which yt-dlp asks for
 * the tokens YouTube wants on some clients' streams. yt-dlp and Deno are checked against the SHA-256 their releases
 * publish; the provider publishes none, so it is only as trusted as GitHub's HTTPS.
 */
final class Tools {
    static final Path DIR = ClientConfig.DIR.resolve("tools");
    private static final String VERSION = ".version";
    private static final long CHECK_EVERY_MILLIS = 24 * 60 * 60 * 1000L;
    /** The PO Token provider's port; only on the loopback address. */
    private static final int POT_PORT = 4416;
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");

    private static final String OS = Platform.isWindows() ? "windows" : Platform.isMac() ? "macos" : "linux";
    private static final boolean ARM = Platform.isARM();
    private static final String EXE = Platform.isWindows() ? ".exe" : "";

    @Nullable
    private static Installed installed;
    @Nullable
    private static Process pot;

    /** Where each program is, once {@link #ensure} has them; null for one this computer has no build of. */
    record Installed(Path ytDlp, Path deno, @Nullable Path pot, @Nullable Path plugins) {
    }

    private Tools() {
    }

    /** Fetches or updates them now, off the thread that would first need them. */
    static void preload() {
        Thread.ofPlatform().daemon().name("CMS tools").start(() -> {
            try {
                ensure();
            } catch (IOException e) {
                LogUtils.getLogger().warn("yt-dlp could not be set up; displays play only what the API resolves: {}",
                        e.getMessage());
            }
        });
    }

    /** The programs, fetched or updated first if need be; fails if yt-dlp or Deno can't be had. */
    static synchronized Installed ensure() throws IOException {
        if (installed != null && !stale()) {
            return installed;
        }
        Files.createDirectories(DIR);
        Path ytDlp = fetch("yt-dlp/yt-dlp", ytDlpAsset(), "yt-dlp" + EXE, Sums.FILE);
        Path deno = fetch("denoland/deno", denoAsset(), "deno" + EXE, Sums.PER_ASSET);
        Path potPath = null;
        Path plugins = null;
        String potAsset = potAsset();
        if (potAsset != null) {
            try {
                potPath = fetch("jim60105/bgutil-ytdlp-pot-provider-rs", potAsset, "bgutil-pot" + EXE, Sums.NONE);
                plugins = DIR.resolve("yt-dlp-plugins");
                Files.createDirectories(plugins);
                // yt-dlp loads a plugin package left zipped in its plugin folder.
                Path zip = fetch("jim60105/bgutil-ytdlp-pot-provider-rs", "bgutil-ytdlp-pot-provider-rs.zip",
                        "bgutil-ytdlp-pot-provider-rs.zip", Sums.NONE);
                Files.copy(zip, plugins.resolve(zip.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LogUtils.getLogger().warn("The PO Token provider could not be set up; YouTube may refuse some videos: {}",
                        e.getMessage());
                potPath = null;
                plugins = null;
            }
        }
        installed = new Installed(ytDlp, deno, potPath, plugins);
        Files.writeString(DIR.resolve(".checked"), Long.toString(System.currentTimeMillis()));
        return installed;
    }

    private static boolean stale() {
        try {
            long checked = Long.parseLong(Files.readString(DIR.resolve(".checked")).trim());
            return System.currentTimeMillis() - checked > CHECK_EVERY_MILLIS;
        } catch (IOException | NumberFormatException e) {
            return true;
        }
    }

    /**
     * The PO Token provider's address, starting it first if it isn't running (another game on this computer may have
     * started it); null if there is none.
     */
    @Nullable
    static synchronized String potServer(Installed tools) {
        if (tools.pot() == null) {
            return null;
        }
        String base = "http://127.0.0.1:" + POT_PORT;
        if (ping(base)) {
            return base;
        }
        if (pot == null || !pot.isAlive()) {
            try {
                pot = new ProcessBuilder(tools.pot().toString(), "server", "--host", "127.0.0.1",
                        "--port", Integer.toString(POT_PORT))
                        .redirectErrorStream(true).redirectOutput(DIR.resolve("bgutil-pot.log").toFile()).start();
                Process started = pot;
                Runtime.getRuntime().addShutdownHook(new Thread(started::destroy));
            } catch (IOException e) {
                LogUtils.getLogger().warn("The PO Token provider could not start: {}", e.getMessage());
                return null;
            }
        }
        for (int i = 0; i < 50; i++) {
            if (ping(base)) {
                return base;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static boolean ping(String base) {
        try {
            return NativeMediaResolver.HTTP.send(HttpRequest.newBuilder(URI.create(base + "/ping")).timeout(Duration.ofSeconds(1)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum Sums {
        /** yt-dlp: one SHA2-256SUMS file for the release. */
        FILE,
        /** Deno: {@code <asset>.sha256sum} beside each asset. */
        PER_ASSET,
        NONE
    }

    private static Path versionFile(String name) {
        return DIR.resolve(name + VERSION);
    }

    /** What is installed, by name, at which release (see fetch); empty before anything is. */
    static Map<String, String> versions() {
        Map<String, String> versions = new java.util.TreeMap<>();
        try (java.util.stream.Stream<Path> files = Files.list(DIR)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(VERSION)).toList()) {
                String name = file.getFileName().toString();
                versions.put(name.substring(0, name.length() - VERSION.length()), Files.readString(file).trim());
            }
        } catch (IOException none) {
            // not set up (yet)
        }
        return versions;
    }

    /**
     * {@code repo}'s latest {@code asset}, installed as {@code name} (unpacked from it if a zip); what is there is
     * kept when it is the latest release already, or when GitHub can't be reached.
     */
    private static Path fetch(String repo, String asset, String name, Sums sums) throws IOException {
        Path target = DIR.resolve(name);
        Path versionFile = versionFile(name);
        String have = Files.exists(target) && Files.exists(versionFile) ? Files.readString(versionFile).trim() : null;
        String latest;
        try {
            latest = latestTag(repo);
        } catch (IOException e) {
            if (have != null) {
                return target; // offline: keep what we have
            }
            throw e;
        }
        if (latest.equals(have)) {
            return target;
        }
        String base = "https://github.com/" + repo + "/releases/download/" + latest + "/";
        LogUtils.getLogger().info("Downloading {} {} ({})", repo, latest, asset);
        byte[] bytes = download(base + asset);
        String expected = switch (sums) {
            case FILE -> sumFor(new String(download(base + "SHA2-256SUMS"), StandardCharsets.UTF_8), asset);
            case PER_ASSET -> sumFor(new String(download(base + asset + ".sha256sum"), StandardCharsets.UTF_8), null);
            case NONE -> null;
        };
        if (expected != null && !expected.equalsIgnoreCase(AudioCache.sha256(bytes))) {
            throw new IOException(asset + " does not match its release's SHA-256");
        }
        Path temp = Files.createTempFile(DIR, name, ".part");
        try {
            if (asset.endsWith(".zip") && !name.endsWith(".zip")) {
                unzipOne(bytes, name, temp);
            } else {
                Files.write(temp, bytes);
            }
            temp.toFile().setExecutable(true);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.writeString(versionFile, latest);
        return target;
    }

    /** The tag of {@code repo}'s latest release, from where GitHub redirects /releases/latest. */
    private static String latestTag(String repo) throws IOException {
        HttpResponse<Void> response = send(HttpRequest.newBuilder(URI.create(
                "https://github.com/" + repo + "/releases/latest")).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.discarding());
        String path = response.uri().getPath();
        int at = path.lastIndexOf("/tag/");
        if (response.statusCode() != 200 || at < 0) {
            throw new IOException("GitHub did not say which " + repo + " release is the latest");
        }
        return path.substring(at + 5);
    }

    /** {@code url}'s bytes; release assets redirect to GitHub's download host (never off HTTPS: Redirect.NORMAL). */
    private static byte[] download(String url) throws IOException {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException {
        try {
            return NativeMediaResolver.HTTP.send(request, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** The SHA-256 a sums file gives for {@code asset} (its only one when null). */
    @Nullable
    static String sumFor(String sums, @Nullable String asset) throws IOException {
        for (String line : sums.split("\\R")) {
            Matcher hash = SHA256.matcher(line);
            if (hash.find() && (asset == null || line.trim().endsWith(" " + asset) || line.trim().endsWith("*" + asset))) {
                return hash.group(1);
            }
        }
        throw new IOException("No SHA-256 published for " + (asset == null ? "the download" : asset));
    }

    /** Writes the zip's entry named {@code name} (at any depth) to {@code target}. */
    private static void unzipOne(byte[] zip, String name, Path target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String entryName = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && entryName.substring(entryName.lastIndexOf('/') + 1).equals(name)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException(name + " is not in the download");
    }

    private static String ytDlpAsset() {
        return switch (OS) {
            case "windows" -> ARM ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
            case "macos" -> "yt-dlp_macos";
            default -> ARM ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        };
    }

    private static String denoAsset() {
        String target = switch (OS) {
            case "windows" -> "pc-windows-msvc";
            case "macos" -> "apple-darwin";
            default -> "unknown-linux-gnu";
        };
        return "deno-" + (ARM ? "aarch64" : "x86_64") + "-" + target + ".zip";
    }

    /** The provider's build for this computer; none for Windows on ARM. */
    @Nullable
    private static String potAsset() {
        if (OS.equals("windows") && ARM) {
            return null;
        }
        return "bgutil-pot-" + OS + "-" + (ARM ? "aarch64" : "x86_64") + EXE;
    }
}
