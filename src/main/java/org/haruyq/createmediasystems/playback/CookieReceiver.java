package org.haruyq.createmediasystems.playback;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.Executors;

import org.haruyq.createmediasystems.client.ClientFiles;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Takes the YouTube cookies the VRCVideoCacher Cookies Exporter browser extension sends (Chrome and Firefox stores):
 * it posts them, in Netscape cookies.txt form, to {@code http://127.0.0.1:9696/api/youtube-cookies} each time
 * YouTube loads in the browser. They are saved for yt-dlp (see YtDlp). Only the loopback address is listened on, and
 * only a request from a browser extension is taken: a web page can post here too, but can't claim an extension's
 * Origin, so it can't make yt-dlp use someone else's account.
 */
final class CookieReceiver {
    static final Path FILE = ClientFiles.DIR.resolve("youtube-cookies.txt");
    private static final int PORT = 9696;
    private static final String PATH = "/api/youtube-cookies";
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private CookieReceiver() {
    }

    static void start() {
        HttpServer server;
        try {
            // The extension posts to 127.0.0.1; the game prefers IPv6, where the loopback address is ::1.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 8);
        } catch (IOException e) {
            // VRCVideoCacher (or another game) has it: that one gets the cookies.
            LogUtils.getLogger().info("Port {} is taken; YouTube cookies from the browser extension go elsewhere", PORT);
            return;
        }
        server.createContext(PATH, CookieReceiver::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // Started from a daemon thread, so its dispatcher is one too and doesn't hold the game open.
        Thread.ofPlatform().daemon().name("CMS cookie receiver").start(server::start);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals(PATH)) {
                exchange.sendResponseHeaders(404, -1);
            } else if (origin == null
                    || !(origin.startsWith("chrome-extension://") || origin.startsWith("moz-extension://"))) {
                exchange.sendResponseHeaders(403, -1);
            } else {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                String cookies = body.length > MAX_BODY_BYTES ? null
                        : youtubeOnly(new String(body, StandardCharsets.UTF_8));
                if (body.length > MAX_BODY_BYTES || cookies == null) {
                    exchange.sendResponseHeaders(body.length > MAX_BODY_BYTES ? 413 : 400, -1);
                    return;
                }
                Files.createDirectories(FILE.getParent());
                Path temp = Files.createTempFile(FILE.getParent(), "youtube-cookies", ".part");
                Files.writeString(temp, cookies);
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                exchange.sendResponseHeaders(200, -1);
                LogUtils.getLogger().info("Received YouTube cookies from the browser extension");
            }
        }
    }

    /** {@code text} as a cookies.txt with only youtube.com's cookies; null if it holds none or isn't one. */
    @Nullable
    static String youtubeOnly(String text) {
        StringBuilder out = new StringBuilder("# Netscape HTTP Cookie File\n");
        int kept = 0;
        for (String line : text.split("\\R")) {
            String[] fields = line.split("\t");
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (fields.length != 7) {
                return null;
            }
            String domain = fields[0].toLowerCase(Locale.ROOT);
            if (domain.equals("youtube.com") || domain.endsWith(".youtube.com")) {
                out.append(line).append('\n');
                kept++;
            }
        }
        return kept > 0 ? out.toString() : null;
    }
}
