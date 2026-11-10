package org.haruyq.createmediasystems.disc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class DiscRunTest {
    private static final long FIRST = 4 * 90_000;

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
