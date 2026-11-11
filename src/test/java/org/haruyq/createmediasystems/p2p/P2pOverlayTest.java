package org.haruyq.createmediasystems.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class P2pOverlayTest {
    private static final long S = 1_000_000_000L;

    @Test
    void aViewerSeesItsHostLink() {
        long now = 100 * S;
        var host = new Transport.LinkInfo(P2pTest.HOST, true, "host/srflx", 23_400_000, 85_200_000, true, 0, now - 151 * S + 100 * S);
        List<P2pOverlay.Line> lines = P2pOverlay.lines(Transport.State.CONNECTED, false, P2pTest.HOST, 3L << 30, List.of(host),
                6_100_000, now, id -> id.equals(P2pTest.HOST) ? "Alice" : "?");
        assertEquals(List.of(
                "CMS P2P: viewing Alice, connected",
                "Host: host/srflx, RTT 23.4 ms, open 0:51",
                "Measured 85.2 Mbps, receiving 6.1 Mbps, 3.00 GB in all"), lines.stream().map(P2pOverlay.Line::text).toList());
        assertEquals(0xFF55FF55, lines.get(1).color());
    }

    @Test
    void aHostSeesEachViewer() {
        UUID other = new UUID(0, 9);
        var measuring = new Transport.LinkInfo(P2pTest.VIEWER, true, "host/host", 0, 10_000_000, false, 2048, 0);
        var connecting = new Transport.LinkInfo(other, false, "direct", 0, 10_000_000, false, 0, 0);
        assertEquals(List.of(
                "CMS P2P: hosting, connected, 1 viewer(s) connected",
                "Bob: host/host, RTT -, pace 10.0 Mbps (measuring), queued 2 KB",
                "Carol: connecting..."),
                P2pOverlay.lines(Transport.State.CONNECTED, true, P2pTest.HOST, 0, List.of(measuring, connecting), 0, 0,
                        id -> id.equals(other) ? "Carol" : "Bob").stream().map(P2pOverlay.Line::text).toList());
    }
}
