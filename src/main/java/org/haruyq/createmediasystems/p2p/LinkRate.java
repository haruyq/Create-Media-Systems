package org.haruyq.createmediasystems.p2p;

/**
 * What the host sends one viewer at, measured on their direct connection: a short probe as it opens sets the
 * ceiling, and every delivery after that lowers it while the connection falls behind or recovers it up to that
 * ceiling. Session thread only.
 */
final class LinkRate {
    /** Until the probe answers. */
    static final long INITIAL_BPS = 10_000_000;
    static final long MIN_BPS = 500_000;
    /** Of what the probe got through: room for the game's own traffic and for the connection to vary. */
    static final double HEADROOM = 0.8;
    /** A delivery's time beyond its paced time that is still on pace: the request, the acknowledgement, a hiccup. */
    private static final long SLACK_NANOS = 500_000_000L;

    private long bps = INITIAL_BPS;
    private long ceiling = Protocol.MAX_BPS;
    private boolean measured;

    long bps() {
        return bps;
    }

    boolean measured() {
        return measured;
    }

    /** {@code bytes} arrived over {@code nanos} of the probe, as the viewer saw them; nothing usable if either is 0. */
    void probed(long bytes, long nanos) {
        measured = true;
        if (bytes > 0 && nanos > 0) {
            ceiling = clamp(Math.round(bytes * 8e9 / nanos * HEADROOM));
            bps = ceiling;
        }
    }

    /** {@code bytes} took {@code nanos} from being queued to being acknowledged. */
    void delivered(long bytes, long nanos) {
        if (bytes <= 0 || nanos <= 0) {
            return;
        }
        double paced = bytes * 8e9 / bps;
        if (nanos > paced * 1.5 + SLACK_NANOS) {
            bps = Math.min(bps, clamp(Math.round(bytes * 8e9 / nanos * 0.9)));
        } else if (nanos <= paced * 1.25 + SLACK_NANOS) {
            bps = Math.min(ceiling, bps + Math.max(250_000, bps / 10));
        }
    }

    private static long clamp(long bps) {
        return Math.max(MIN_BPS, Math.min(Protocol.MAX_BPS, bps));
    }
}
