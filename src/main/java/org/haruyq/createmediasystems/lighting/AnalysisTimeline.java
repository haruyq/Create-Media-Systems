package org.haruyq.createmediasystems.lighting;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

public final class AnalysisTimeline {
    private final NavigableMap<Long, AnalysisFrame> frames = new TreeMap<>();
    private final NavigableMap<Long, AnalysisFrame> live = new TreeMap<>();
    private boolean prepared;

    public boolean add(List<AnalysisFrame> batch, boolean cached) {
        if (!valid(batch, cached)) {
            return false;
        }
        prepared |= cached;
        NavigableMap<Long, AnalysisFrame> target = cached ? frames : live;
        for (AnalysisFrame frame : batch) {
            if (cached) {
                target.putIfAbsent(frame.millis(), frame);
            } else {
                target.put(frame.millis(), frame);
            }
        }
        while (target.size() > (cached ? AnalysisFrame.MAX_FRAMES : 600)) {
            target.pollFirstEntry();
        }
        return true;
    }

    public boolean publishPrepared(List<AnalysisFrame> batch) {
        if (!valid(batch, true)) {
            return false;
        }
        if (frames.isEmpty()) {
            return add(batch, true);
        }
        AnalysisFrame previous = frames.lastEntry().getValue();
        List<AnalysisFrame> appended = batch.stream().filter(frame -> frame.millis() > previous.millis()).toList();
        if (appended.isEmpty()) {
            return true;
        }
        AnalysisFrame first = appended.getFirst();
        double offset = previous.at(first.millis()).motionPhase() - first.motionPhase();
        return add(appended.stream().map(frame -> frame.phase(frame.motionPhase() + offset)).toList(), true);
    }

    private static boolean valid(List<AnalysisFrame> batch, boolean cached) {
        long previous = -1;
        for (AnalysisFrame frame : batch) {
            if (!frame.valid() || frame.millis() <= previous || cached && frame.millis() > AnalysisFrame.MAX_DURATION) {
                return false;
            }
            previous = frame.millis();
        }
        return true;
    }

    @Nullable
    public AnalysisFrame sample(long millis) {
        return sample(millis, 0);
    }

    @Nullable
    public AnalysisFrame sample(long millis, long liveDelay) {
        AnalysisFrame cached = samplePrepared(millis);
        return cached == null ? sample(live, millis - liveDelay) : cached;
    }

    @Nullable
    public AnalysisFrame samplePrepared(long millis) {
        return frames.isEmpty() || millis >= frames.lastKey() + AnalysisFrame.INTERVAL ? null : sample(frames, millis);
    }

    public long liveEnd() {
        return live.isEmpty() ? -1 : live.lastKey();
    }

    public void clearLive() {
        live.clear();
    }

    public double phaseAt(long millis, long liveDelay) {
        AnalysisFrame current = sample(millis, liveDelay);
        return current == null ? phaseAt(millis - liveDelay) : current.motionPhase();
    }

    public double phaseAt(long millis) {
        AnalysisFrame current = sample(millis);
        if (current != null) {
            return current.motionPhase();
        }
        AnalysisFrame previous = floorFrame(millis);
        return previous == null ? Double.NaN : previous.at(millis).motionPhase();
    }

    @Nullable
    private AnalysisFrame floorFrame(long millis) {
        var cached = frames.floorEntry(millis);
        var recent = live.floorEntry(millis);
        if (recent != null && (cached == null || recent.getKey() > cached.getKey())) {
            return recent.getValue();
        }
        return cached == null ? null : cached.getValue();
    }

    public static double continuePhase(@Nullable AnalysisTimeline timeline, @Nullable AnalysisFrame previous, long millis) {
        AnalysisFrame received = timeline == null ? null : timeline.floorFrame(millis);
        if (previous != null && previous.millis() <= millis && (received == null || previous.millis() >= received.millis())) {
            return previous.at(millis).motionPhase();
        }
        return timeline == null ? Double.NaN : timeline.phaseAt(millis);
    }

    public static double phaseAt(List<AnalysisFrame> frames, long millis) {
        int low = 0;
        int high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).millis() <= millis) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        if (low == 0) {
            return Double.NaN;
        }
        AnalysisFrame before = frames.get(low - 1);
        return low == frames.size() ? before.at(millis).motionPhase()
                : before.blend(frames.get(low), millis).motionPhase();
    }

    public static List<AnalysisFrame> align(List<AnalysisFrame> frames, long millis, double phase) {
        double current = phaseAt(frames, millis);
        if (!Double.isFinite(current) || !Double.isFinite(phase)) {
            return frames;
        }
        double offset = phase - current;
        List<AnalysisFrame> aligned = new ArrayList<>(frames.size());
        for (AnalysisFrame frame : frames) {
            aligned.add(frame.phase(frame.motionPhase() + offset));
        }
        return List.copyOf(aligned);
    }

    private static AnalysisFrame sample(NavigableMap<Long, AnalysisFrame> values, long millis) {
        var before = values.floorEntry(millis);
        if (before == null || millis - before.getKey() > 350) {
            return null;
        }
        var after = values.higherEntry(before.getKey());
        if (after != null && after.getKey() - before.getKey() > 250 && millis != before.getKey()) {
            return null;
        }
        return after == null ? before.getValue().at(millis) : before.getValue().blend(after.getValue(), millis);
    }

    public List<AnalysisFrame> after(long millis, int limit) {
        List<AnalysisFrame> result = new ArrayList<>();
        for (AnalysisFrame frame : (prepared ? frames : live).tailMap(millis, false).values()) {
            result.add(frame);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public boolean prepared() {
        return prepared;
    }

    public long end() {
        return Math.max(frames.isEmpty() ? -1 : frames.lastKey(), live.isEmpty() ? -1 : live.lastKey());
    }
}
