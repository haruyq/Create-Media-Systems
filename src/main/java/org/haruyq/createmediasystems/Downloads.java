package org.haruyq.createmediasystems;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jetbrains.annotations.Nullable;

/** SHA-256, and downloads checked against one. */
public final class Downloads {
    private Downloads() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** In lower-case hex. */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /**
     * {@code uri}'s body into {@code out}, failing past {@code maxBytes} or {@code timeout}, or unless it hashes to
     * {@code sha256} (hex, either case). Errors name it {@code what} and end with {@code hint}, if any. The body is
     * read on a worker, so interrupting the caller gives up on a stalled server at once.
     */
    public static void verified(HttpClient http, URI uri, OutputStream out, String sha256, long maxBytes, Duration timeout,
                                String what, @Nullable String hint) throws IOException {
        String retry = hint == null ? "" : "; " + hint;
        try {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body(); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    if (response.statusCode() != 200 || response.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes) {
                        throw new IOException(what + " download failed (HTTP " + response.statusCode() + ")" + retry);
                    }
                    var copy = worker.submit(() -> {
                        MessageDigest hash = sha256();
                        byte[] buffer = new byte[64 * 1024];
                        long total = 0;
                        for (int read; (read = input.read(buffer)) != -1;) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IOException(what + " setup was cancelled");
                            }
                            if ((total += read) > maxBytes) {
                                throw new IOException(what + " download exceeded its size limit");
                            }
                            hash.update(buffer, 0, read);
                            out.write(buffer, 0, read);
                        }
                        return HexFormat.of().formatHex(hash.digest());
                    });
                    if (!sha256.equalsIgnoreCase(copy.get(timeout.toMillis(), TimeUnit.MILLISECONDS))) {
                        throw new IOException(what + " download failed SHA-256 verification" + retry);
                    }
                } catch (ExecutionException failed) {
                    throw new IOException(what + " download failed; check your connection" + (hint == null ? "" : " and " + hint),
                            failed.getCause());
                } catch (TimeoutException timedOut) {
                    throw new IOException(what + " download timed out" + retry);
                } finally {
                    input.close();
                    worker.shutdownNow();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " setup was cancelled");
        }
    }
}
