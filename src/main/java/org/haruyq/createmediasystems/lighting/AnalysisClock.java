package org.haruyq.createmediasystems.lighting;

public final class AnalysisClock {
    private long lastSent = Long.MIN_VALUE;
    private long anchorAt = Long.MIN_VALUE;
    private double anchorMillis;
    private double upstream;
    private double speed = 1;

    public void receive(long now, long sent, long serverMillis) {
        long roundTrip = now - sent;
        if (sent <= lastSent || roundTrip < 0 || roundTrip > 4_000_000_000L || serverMillis < -1 || serverMillis > 604_800_000) {
            return;
        }
        double oneWay = roundTrip / 2_000_000.0;
        upstream = lastSent == Long.MIN_VALUE ? oneWay : upstream + (oneWay - upstream) * 0.25;
        lastSent = sent;
        if (serverMillis == -1) {
            return;
        }
        double target = serverMillis + oneWay;
        if (anchorAt == Long.MIN_VALUE) {
            anchorMillis = target;
        } else {
            anchorMillis = projected(now);
            speed = 1 + Math.clamp((target - anchorMillis) / 2000, -0.2, 0.2);
        }
        anchorAt = now;
    }

    public long position(long now, long fallbackMillis) {
        return anchorAt == Long.MIN_VALUE ? fallbackMillis : Math.round(projected(now));
    }

    public int upstreamMillis() {
        return (int) Math.round(upstream);
    }

    public void reset() {
        lastSent = Long.MIN_VALUE;
        anchorAt = Long.MIN_VALUE;
        upstream = 0;
        speed = 1;
    }

    private double projected(long now) {
        return anchorMillis + Math.max(0, now - anchorAt) / 1_000_000.0 * speed;
    }
}
