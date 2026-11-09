package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsing and errors only: the CLI here is a stand-in process, so nothing is measured. */
class SpeedtestTest {
    /** A real Ookla CLI 1.2.0 result, its addresses and server left out. */
    static final String RESULT = "{\"type\":\"result\",\"timestamp\":\"2026-11-09T12:39:50Z\",\"ping\":{\"latency\":4.246,"
            + "\"jitter\":2.244},\"download\":{\"bandwidth\":117158413},\"upload\":{\"bandwidth\":118126434},\"packetLoss\":0}";
    static final String LICENSE = "You may only use this Speedtest software and information generated\nfrom it for personal, "
            + "non-commercial use... End User License Agreement\nDo you accept the license? [type YES to accept]: ";

    @TempDir
    Path dir;

    @Test
    void convertsBytesPerSecondToBits() throws Exception {
        Instant now = Instant.parse("2026-11-09T12:40:00Z");
        Speedtest.Result result = Speedtest.parse("noise\n" + RESULT + "\n", 0, now);
        assertEquals(937_267_304L, result.downloadBps());
        assertEquals(945_011_472L, result.uploadBps());
        assertTrue(result.fresh(now.plus(Duration.ofMinutes(59))));
        assertTrue(!result.fresh(now.plus(Duration.ofHours(1))));
    }

    @Test
    void refusesRatesOutOfRange() {
        for (String bandwidth : List.of("0", "-5", "12500000001", "1e300", "\"fast\"", "NaN")) {
            assertThrows(Speedtest.Failure.class, () -> Speedtest.parse(RESULT.replace("117158413", bandwidth), 0, Instant.now()), bandwidth);
        }
        assertThrows(Speedtest.Failure.class, () -> Speedtest.parse("{\"type\":\"log\",\"message\":\"x\"}", 2, Instant.now()));
        for (String odd : List.of("{\"type\":null}", "{\"type\":[]}", "{}", "[]", "{\"type\":\"result\"}")) {
            assertThrows(Speedtest.Failure.class, () -> Speedtest.parse(odd, 0, Instant.now()), odd);
        }
    }

    @Test
    void aFailedCliMeasuredNothing() {
        Speedtest.Failure failure = assertThrows(Speedtest.Failure.class, () -> Speedtest.parse(RESULT, 3, Instant.now()));
        assertTrue(failure.getMessage().contains("exit code 3"));
        assertThrows(Speedtest.Failure.class, () -> run("failed", Duration.ofSeconds(60)));
    }

    @Test
    void neverAcceptsTheLicenseForThePlayer() throws Exception {
        Speedtest.Failure failure = assertThrows(Speedtest.Failure.class, () -> run("license", Duration.ofSeconds(60)));
        assertTrue(failure.getMessage().contains("accept its license"));
        assertTrue(Files.readString(dir.resolve("args.txt")).strip().equals("--format=json --progress=no"),
                "no --accept-license or --accept-gdpr");
    }

    @Test
    void readsAResultFromTheProcess() throws Exception {
        assertEquals(945_011_472L, run("result", Duration.ofSeconds(60)).uploadBps());
    }

    @Test
    void reportsAMissingCliTimeoutsAndCancels() throws Exception {
        assertThrows(Speedtest.Failure.class, () -> Speedtest.run(List.of(dir.resolve("missing.exe").toString()), Duration.ofSeconds(5)));
        assertThrows(Speedtest.Failure.class, () -> run("hang", Duration.ofSeconds(2)));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                run("hang", Duration.ofSeconds(60));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        Thread.sleep(1500);
        thread.interrupt();
        thread.join(10_000);
        assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
    }

    /** Runs a stand-in CLI: Java's source launcher on a file that acts as {@code mode} says. */
    private Speedtest.Result run(String mode, Duration timeout) throws Exception {
        Path source = dir.resolve("Cli.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class Cli {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[1], "args.txt"), String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
                        switch (args[0]) {
                            case "result" -> System.out.println(%1$s);
                            case "failed" -> { System.out.println(%1$s); System.exit(3); }
                            case "license" -> { System.err.print(%2$s); System.exit(System.in.read() < 0 ? 1 : 0); }
                            default -> Thread.sleep(600_000);
                        }
                    }
                }
                """.formatted(quote(RESULT), quote(LICENSE)));
        String java = ProcessHandle.current().info().command().orElseThrow();
        return Speedtest.run(List.of(java, source.toString(), mode, dir.toString()), timeout);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
