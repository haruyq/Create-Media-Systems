package org.haruyq.createmediasystems.lighting;

public record AnalysisDelay(int from, int to, long start) {
    public static final AnalysisDelay INITIAL = new AnalysisDelay(200, 200, 0);

    public double at(long millis) {
        double amount = Math.clamp((millis - start) / 2000.0, 0, 1);
        return from + (to - from) * amount;
    }

    public AnalysisDelay increase(int lagMillis, long millis) {
        if (lagMillis < 0 || lagMillis > 2000 || millis < start + 2000) {
            return this;
        }
        int target = Math.min(2000, (lagMillis + 150 + 49) / 50 * 50);
        return target > to + 50 ? new AnalysisDelay(to, target, millis + 1000) : this;
    }
}
