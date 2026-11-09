package org.haruyq.createmediasystems.p2p;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * This computer's bandwidth, measured by Ookla's Speedtest CLI that its player installed (speedtest.net/apps/cli).
 * CMS never accepts Ookla's license or privacy notice for them: a CLI that asks for it fails here, and the player
 * accepts it by running it once themselves. Only the two rates and when they were measured are kept; the CLI's
 * output, which names this computer's addresses, is not.
 */
public final class Speedtest {
    /** How long a measurement sizes P2P sessions for. */
    public static final Duration FRESH = Duration.ofHours(1);
    static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_OUTPUT = 256 * 1024;
    /** {@link Protocol#MAX_BPS} in the CLI's bytes per second. */
    private static final BigDecimal MAX_BYTES_PER_SECOND = BigDecimal.valueOf(Protocol.MAX_BPS / 8);

    public record Result(long downloadBps, long uploadBps, Instant measuredAt) {
        public boolean fresh(Instant now) {
            return now.isBefore(measuredAt.plus(FRESH));
        }
    }

    /** Why a measurement failed, said to the player as is. */
    public static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private Speedtest() {
    }

    /**
     * Measures with the CLI at {@code executable} (a path, or a name on PATH). Blocks up to {@link #TIMEOUT}; an
     * interrupt stops the CLI and throws InterruptedException.
     */
    public static Result run(String executable) throws Failure, InterruptedException {
        return run(List.of(executable), TIMEOUT);
    }

    static Result run(List<String> executable, Duration timeout) throws Failure, InterruptedException {
        List<String> command = new ArrayList<>(executable);
        command.addAll(List.of("--format=json", "--progress=no"));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException | RuntimeException e) {
            throw new Failure("Ookla's Speedtest CLI is not at '" + String.join(" ", executable)
                    + "'. Install it from speedtest.net/apps/cli, or set speedtestPath in cms-client.toml.");
        }
        try {
            // Nothing on stdin: a license prompt reads end of input instead of waiting on a player who can't see it.
            process.getOutputStream().close();
            InputStream stdout = process.getInputStream();
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                byte[] buffer = new byte[8192];
                try {
                    for (int n; (n = stdout.read(buffer)) >= 0; ) {
                        synchronized (kept) {
                            kept.write(buffer, 0, Math.min(n, Math.max(0, MAX_OUTPUT - kept.size())));
                        }
                    }
                } catch (IOException e) {
                    // The CLI ended or was stopped.
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new Failure("The speedtest took longer than " + timeout.toSeconds() + " s and was stopped.");
            }
            reader.join(Duration.ofSeconds(5));
            String out;
            synchronized (kept) {
                out = kept.toString(StandardCharsets.UTF_8);
            }
            return parse(out, process.exitValue(), Instant.now());
        } catch (IOException e) {
            throw new Failure("The speedtest failed: " + e.getMessage());
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** The result in the CLI's {@code --format=json} output, measured at {@code now}. */
    static Result parse(String output, int exitCode, Instant now) throws Failure {
        // A CLI that failed measured nothing usable, whatever it printed.
        for (String line : exitCode == 0 ? output.lines().toList().reversed() : List.<String>of()) {
            JsonObject json;
            try {
                json = JsonParser.parseString(line).getAsJsonObject();
                if (!"result".equals(json.get("type").getAsString())) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;
            }
            return new Result(bitsPerSecond(json, "download"), bitsPerSecond(json, "upload"), now);
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("license") || lower.contains("gdpr") || lower.contains("privacy")) {
            throw new Failure("Ookla's Speedtest CLI needs you to accept its license first: run it once in a "
                    + "terminal and read it yourself. CMS never accepts it for you.");
        }
        throw new Failure(exitCode == 0 ? "The speedtest gave no result." : "The speedtest failed (exit code " + exitCode + ").");
    }

    /** The CLI's {@code <direction>.bandwidth}, bytes per second, in bits per second. */
    private static long bitsPerSecond(JsonObject json, String direction) throws Failure {
        try {
            BigDecimal bytes = json.getAsJsonObject(direction).get("bandwidth").getAsBigDecimal();
            if (bytes.signum() > 0 && bytes.compareTo(MAX_BYTES_PER_SECOND) <= 0) {
                long bits = bytes.multiply(BigDecimal.valueOf(8)).longValue();
                if (Protocol.validBps(bits)) {
                    return bits;
                }
            }
        } catch (RuntimeException e) {
            // Missing or not a number: said below.
        }
        throw new Failure("The speedtest gave no usable " + direction + " rate.");
    }
}
