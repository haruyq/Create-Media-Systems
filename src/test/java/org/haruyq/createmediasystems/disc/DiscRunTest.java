package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscRunTest {
    private static final long FIRST = 4 * 90_000;

    @Test
    void keepsTheLastErrorAndWaitsForStderrBeforeReportingFailure(@TempDir Path directory) throws Exception {
        Path helper = directory.resolve("FailedReader.java");
        Files.writeString(helper, "class FailedReader { public static void main(String[] args) { "
                + "System.err.print(\"x\".repeat(12000)); System.err.println(\"Unable to read DVD sector 1234\"); System.exit(1); } }");
        try (DiscRun run = new DiscRun(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), helper.toString()),
                null, new DiscSource.Quality(480, 30, 1100), "libx264", 0)) {
            IOException failure = assertThrows(IOException.class, run::take);
            assertNotNull(failure.getCause());
            assertTrue(failure.getCause().getMessage().endsWith("Unable to read DVD sector 1234"));
        }
    }

    @Test
    void cutsOnVideoBoundariesAndLeadsEachSegmentWithTheTables() throws Exception {
        byte[] sdt = packet(0x11, false, new byte[]{0, 0x42}), pat = packet(0, true, new byte[]{0, 0, (byte) 0xb0, 13, 0, 1, (byte) 0xc1, 0, 0,
                0, 1, (byte) 0xf0, 0}), pmt = packet(0x1000, true, new byte[]{0, 2});
        ByteArrayOutputStream ts = new ByteArrayOutputStream();
        for (byte[] p : List.of(sdt, pat, pmt, video(FIRST), audio(), video(FIRST + 3000), video(FIRST + 360_000), audio(),
                video(FIRST + 719_500), video(FIRST + 1_080_000 + 2_000_000))) {
            ts.write(p);
        }
        List<byte[]> segments = new ArrayList<>();
        DiscRun.split(new ByteArrayInputStream(ts.toByteArray()), 1 << 20, segments::add);
        assertEquals(4, segments.size(), "a gap past several boundaries still cuts once");
        assertEquals(6 * 188, segments.get(0).length);
        for (byte[] segment : segments.subList(1, 4)) {
            assertArrayEquals(sdt, Arrays.copyOfRange(segment, 0, 188));
            assertArrayEquals(pat, Arrays.copyOfRange(segment, 188, 376));
            assertArrayEquals(pmt, Arrays.copyOfRange(segment, 376, 564));
        }
        assertEquals(FIRST + 360_000, DiscRun.videoPts(Arrays.copyOfRange(segments.get(1), 564, 752)));
        assertEquals(FIRST + 719_500, DiscRun.videoPts(Arrays.copyOfRange(segments.get(2), 564, 752)), "within a frame of the boundary");
        assertEquals(-1, DiscRun.videoPts(audio()));
    }

    @Test
    void goesOnOnlyWhereANewPassWouldBeNoSooner() {
        long second = 1_000_000_000L;
        assertTrue(DiscRun.sooner(0, 16 * second, 6 * second), "its next segment is always its own");
        // A real drive on a CSS disc: ten seconds to seek, six to read each segment.
        assertTrue(DiscRun.sooner(1, 16 * second, 6 * second));
        assertFalse(DiscRun.sooner(2, 16 * second, 6 * second), "three segments to read take longer than seeking there");
        // A folder on a fast disk: under a second to start, a tenth for a segment.
        assertTrue(DiscRun.sooner(6, second, second / 10));
        assertFalse(DiscRun.sooner(12, second, second / 10));
        assertTrue(DiscRun.sooner(DiscRun.REACH, 5 * second, 0), "until it knows how fast it reads");
        assertFalse(DiscRun.sooner(DiscRun.REACH + 1, 5 * second, 0));
    }

    private static byte[] video(long pts) {
        return packet(0x100, true, new byte[]{0, 0, 1, (byte) 0xe0, 0, 0, (byte) 0x80, (byte) 0x80, 5,
                (byte) (0x21 | pts >> 29 & 0x0e), (byte) (pts >> 22), (byte) (pts >> 14 | 1), (byte) (pts >> 7), (byte) (pts << 1 | 1)});
    }

    private static byte[] audio() {
        return packet(0x101, true, new byte[]{0, 0, 1, (byte) 0xbd, 0, 0, (byte) 0x80, (byte) 0x80, 5, 0x21, 0, 1, 0, 1});
    }

    private static byte[] packet(int pid, boolean start, byte[] payload) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xff);
        packet[0] = 0x47;
        packet[1] = (byte) ((start ? 0x40 : 0) | pid >> 8);
        packet[2] = (byte) pid;
        packet[3] = 0x10;
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }
}
