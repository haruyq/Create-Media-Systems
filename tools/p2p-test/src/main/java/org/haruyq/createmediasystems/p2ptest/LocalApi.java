package org.haruyq.createmediasystems.p2ptest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CMS-API's own P2P signaling, alone, on this computer's loopback for a local demo or test: a random port and test
 * token, no STUN, no .env, and none of the rest of the API (its cookies, updater or media). Started with uv from a
 * CMS-API checkout; closing it ends it and what it started.
 */
final class LocalApi implements AutoCloseable {
    private static final int LOG_LINES = 40;
    private static final String SERVER = "import sys, uvicorn; from fastapi import FastAPI; from app.routers import p2p; "
            + "app = FastAPI(); app.include_router(p2p.router); "
            + "uvicorn.run(app, host='127.0.0.1', port=int(sys.argv[1]), log_level='warning', access_log=False)";

    final String url;
    final String token;
    private final Process process;
    /** The API's last lines of output, for when it fails to start. */
    private final ArrayDeque<String> log = new ArrayDeque<>();

    private LocalApi(String url, String token, Process process) {
        this.url = url;
        this.token = token;
        this.process = process;
    }

    static LocalApi start(Path dir) throws IOException, InterruptedException {
        if (!Files.isRegularFile(dir.resolve("app/routers/p2p.py"))) {
            throw new IOException("CMS-API が見つかりません: " + dir.toAbsolutePath()
                    + "。CMS-API を取得したフォルダーを指定してください。");
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        ProcessBuilder builder = new ProcessBuilder("uv", "run", "--no-env-file", "--frozen", "python", "-c", SERVER,
                Integer.toString(port)).directory(dir.toFile()).redirectErrorStream(true);
        // The secret goes in the environment only, never on the command line.
        builder.environment().putAll(Map.of("P2P_SERVER_TOKEN", token, "P2P_STUN_URLS", ""));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("uv を起動できません（" + e.getMessage() + "）。uv を入れて PATH に通してください。", e);
        }
        LocalApi api = new LocalApi("http://127.0.0.1:" + port, token, process);
        Thread.ofPlatform().daemon().name("CMS-API output").start(api::readLog);
        try {
            api.awaitReady();
        } catch (IOException | InterruptedException | RuntimeException e) {
            api.close();
            throw e;
        }
        return api;
    }

    private void readLog() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                synchronized (log) {
                    log.addLast(line.length() > 300 ? line.substring(0, 300) : line);
                    if (log.size() > LOG_LINES) {
                        log.removeFirst();
                    }
                }
            }
        } catch (IOException e) {
            // Ended.
        }
    }

    /** Waits (2 minutes at most: uv may set up its environment first) until the API answers with P2P on. */
    private void awaitReady() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/p2p/config")).timeout(Duration.ofSeconds(2)).build();
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (System.nanoTime() < end) {
            if (!process.isAlive()) {
                throw new IOException("ローカルの CMS-API が起動中に終了しました:\n" + tail());
            }
            try {
                HttpResponse<String> response = Session.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"enabled\":true")) {
                    return;
                }
            } catch (IOException e) {
                // Not listening yet.
            }
            Thread.sleep(250);
        }
        throw new IOException("ローカルの CMS-API が2分以内に起動しませんでした:\n" + tail());
    }

    String tail() {
        synchronized (log) {
            return String.join("\n", log);
        }
    }

    /** Whether the API, or anything it started, still runs. */
    boolean alive() {
        return process.isAlive() || process.descendants().anyMatch(ProcessHandle::isAlive);
    }

    @Override
    public void close() {
        // uv runs the API as its child: end both.
        List<ProcessHandle> started = process.descendants().toList();
        started.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
            for (ProcessHandle child : started) {
                child.onExit().get(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // Reported by alive().
        }
    }
}
