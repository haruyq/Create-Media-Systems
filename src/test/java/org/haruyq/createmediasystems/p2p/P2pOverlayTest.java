package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class P2pOverlayTest {
    private static final long S = 1_000_000_000L;

    private static void says(P2pOverlay.Line line, String... facts) {
        for (String fact : facts) {
            assertTrue(line.text().contains(fact), fact + " in " + line.text());
        }
    }

    @Test
    void aViewerSeesItsHostLink() {
        long now = 100 * S;
        var host = new Transport.LinkInfo(P2pTest.HOST, true, "host/srflx", 23_400_000, 85_200_000, true, 0, now - 151 * S + 100 * S);
        List<P2pOverlay.Line> lines = P2pOverlay.lines(Transport.State.CONNECTED, false, P2pTest.HOST, 3L << 30, List.of(host),
                6_100_000, now, id -> id.equals(P2pTest.HOST) ? "Alice" : "?");
        assertEquals(3, lines.size(), lines.toString());
        says(lines.get(0), "Alice");
        says(lines.get(1), "host/srflx", "23.4");
        says(lines.get(2), "85.2", "6.1");
        var connecting = new Transport.LinkInfo(P2pTest.HOST, false, "direct", 0, 0, false, 0, 0);
        assertNotEquals(lines.get(1).color(), P2pOverlay.lines(Transport.State.CONNECTED, false, P2pTest.HOST, 0,
                List.of(connecting), 0, now, id -> "Alice").get(1).color(), "a link up shows apart from one still connecting");
    }

    @Test
    void aHostSeesEachViewer() {
        UUID other = new UUID(0, 9);
        var measuring = new Transport.LinkInfo(P2pTest.VIEWER, true, "host/host", 0, 10_000_000, false, 2048, 0);
        var connecting = new Transport.LinkInfo(other, false, "direct", 0, 10_000_000, false, 0, 0);
        List<P2pOverlay.Line> lines = P2pOverlay.lines(Transport.State.CONNECTED, true, P2pTest.HOST, 0,
                List.of(measuring, connecting), 0, 0, id -> id.equals(other) ? "Carol" : "Bob");
        assertEquals(3, lines.size(), "a line for itself and one for each viewer: " + lines);
        says(lines.get(0), "1");
        says(lines.get(1), "Bob", "host/host", "10.0");
        says(lines.get(2), "Carol");
        assertFalse(lines.get(2).text().contains("direct"), "a viewer still connecting has no route yet: " + lines.get(2));
    }
}
