package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class DiscToolsTest {
    @TempDir Path directory;

    @Test
    void cancelsAStalledDownloadAndCleansUp() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loading = Thread.ofPlatform().daemon().start(() -> {
            try {
                DiscTools.install(directory, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "0".repeat(64), () -> {});
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            loading.interrupt();
            loading.join(5000);
            assertFalse(loading.isAlive(), "Cancelling must not wait for the download server");
            assertInstanceOf(IOException.class, failure.get());
            try (var files = Files.list(directory)) {
                assertEquals(List.of(".lock"), files.map(file -> file.getFileName().toString()).toList());
            }
        } finally {
            release.countDown();
            server.stop(0);
            loading.interrupt();
            loading.join(5000);
        }
    }

    @Test
    void preservesCustomToolsWithoutDownloading() throws Exception {
        var tools = DiscTools.ensure(directory, "C:/Custom/ffmpeg.exe", "C:/Custom/ffprobe.exe", () -> fail("Downloaded custom tools"));
        assertEquals("C:/Custom/ffmpeg.exe", tools.ffmpeg());
        assertEquals("C:/Custom/ffprobe.exe", tools.ffprobe());
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void verifiesDownloadsReusesThemOfflineAndRepairsMissingLibraries() throws Exception {
        byte[] archive = archive(true);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ffmpeg.zip", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, archive.length);
                exchange.getResponseBody().write(archive);
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ffmpeg.zip");
        AtomicInteger downloads = new AtomicInteger();
        try {
            assertThrows(IOException.class, () -> DiscTools.install(directory, uri, "0".repeat(64), downloads::incrementAndGet));
            try (var files = Files.list(directory)) {
                assertEquals(List.of(".lock"), files.map(file -> file.getFileName().toString()).toList());
            }
            Path installed = DiscTools.install(directory, uri, hash, downloads::incrementAndGet);
            assertTrue(Files.isRegularFile(installed.resolve("ffmpeg.exe")));
            assertTrue(Files.isRegularFile(installed.resolve("ffprobe.exe")));
            assertTrue(Files.isRegularFile(installed.resolve("LICENSE")));
            assertFalse(Files.exists(directory.resolve("escaped.exe")));
            Files.delete(installed.resolve("libstdc++-6.dll"));
            assertEquals(installed, DiscTools.install(directory, uri, hash, downloads::incrementAndGet));
            assertTrue(Files.isRegularFile(installed.resolve("libstdc++-6.dll")));
            assertEquals(3, downloads.get());
            server.stop(0);
            assertEquals(installed, DiscTools.install(directory, uri, hash, () -> fail("Cached tools must work offline")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsIncompleteArchives() throws Exception {
        byte[] archive = archive(false);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, archive.length);
                exchange.getResponseBody().write(archive);
            }
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> DiscTools.install(directory,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), hash, () -> {}));
            assertFalse(Files.exists(directory.resolve("9.0.2-cms-dvd1-win64")));
        } finally {
            server.stop(0);
        }
    }

    private static byte[] archive(boolean complete) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : List.of("bin/ffmpeg.exe", "bin/ffprobe.exe", "bin/libstdc++-6.dll", "LICENSE", "README.txt", "bin/../../escaped.exe")) {
                if (!complete && name.endsWith("ffprobe.exe")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry("ffmpeg-9.0.2-cms-dvd1/" + name));
                zip.write(new byte[]{1, 2, 3});
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
