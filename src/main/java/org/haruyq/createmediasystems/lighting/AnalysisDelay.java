package org.haruyq.createmediasystems.lighting;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * How far behind its track live frames are played (ms): {@code from}, turning to {@code to} over RAMP from
 * {@code start} into the track, so every viewer sees them only once the slowest has them.
 */
public record AnalysisDelay(int from, int to, long start) {
    public static final AnalysisDelay INITIAL = new AnalysisDelay(200, 200, 0);
    /** Longest delay, and longest lag it makes up for. */
    public static final int MAX_MILLIS = 2000;
    /** How long a change of delay takes (ms), and how long one stands before the next. */
    static final int RAMP_MILLIS = 2000;
    public static final StreamCodec<ByteBuf, AnalysisDelay> CODEC = StreamCodec.of(
            (buf, delay) -> buf.writeInt(delay.from).writeInt(delay.to).writeLong(delay.start),
            buf -> new AnalysisDelay(buf.readInt(), buf.readInt(), buf.readLong()));

    public double at(long millis) {
        double amount = Math.clamp((millis - start) / (double) RAMP_MILLIS, 0, 1);
        return from + (to - from) * amount;
    }

    public AnalysisDelay increase(int lagMillis, long millis) {
        if (lagMillis < 0 || lagMillis > MAX_MILLIS || millis < start + RAMP_MILLIS) {
            return this;
        }
        int target = Math.min(MAX_MILLIS, (lagMillis + 150 + 49) / 50 * 50);
        return target > to + 50 ? new AnalysisDelay(to, target, millis + 1000) : this;
    }
}
