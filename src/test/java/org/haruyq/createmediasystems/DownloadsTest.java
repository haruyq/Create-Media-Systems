package org.haruyq.createmediasystems;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class DownloadsTest {
    private static final byte[] BODY = "media".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsTheHashInEitherCaseAndRefusesAnotherOrTooMuch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                // Chunked at /chunked: no Content-Length to refuse it by up front.
                exchange.sendResponseHeaders(200, exchange.getRequestURI().getPath().equals("/chunked") ? 0 : BODY.length);
                exchange.getResponseBody().write(BODY);
            }
        });
        server.start();
        try (HttpClient http = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            String sha256 = Downloads.sha256(BODY);
            var out = new ByteArrayOutputStream();
            Downloads.verified(http, uri, out, sha256.toUpperCase(), BODY.length, Duration.ofSeconds(10), "Test", null);
            assertArrayEquals(BODY, out.toByteArray());
            IOException wrong = assertThrows(IOException.class, () -> Downloads.verified(http, uri, new ByteArrayOutputStream(),
                    "0".repeat(64), BODY.length, Duration.ofSeconds(10), "Test", "retry"));
            assertEquals("Test download failed SHA-256 verification; retry", wrong.getMessage());
            assertThrows(IOException.class, () -> Downloads.verified(http, uri, new ByteArrayOutputStream(),
                    sha256, BODY.length - 1, Duration.ofSeconds(10), "Test", null));
            IOException chunked = assertThrows(IOException.class, () -> Downloads.verified(http, uri.resolve("/chunked"),
                    new ByteArrayOutputStream(), sha256, BODY.length - 1, Duration.ofSeconds(10), "Test", null));
            assertEquals("Test download exceeded its size limit", chunked.getCause().getMessage());
        } finally {
            server.stop(0);
        }
    }
}
