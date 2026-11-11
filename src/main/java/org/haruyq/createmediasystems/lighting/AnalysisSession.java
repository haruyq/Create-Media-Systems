package org.haruyq.createmediasystems.lighting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * One source's lighting analysis as both sides keep it (the server's AnalysisCoordinator, each client's
 * AnalysisPlayback): the track it plays and the next, their timelines, and the delay live frames are played at.
 */
class AnalysisSession {
    /** The most sources one level (server) or one client follows at once. */
    static final int MAX_SESSIONS = 32;
    /** A client says how it plays this often (ns); the server takes at most two a period from each (see status). */
    static final long STATUS_NANOS = 1_000_000_000L;
    static final long STATUS_MIN_MILLIS = STATUS_NANOS / 2_000_000;

    String track = "";
    String next = "";
    AnalysisDelay delay = AnalysisDelay.INITIAL;
    final Map<String, AnalysisTimeline> tracks = new LinkedHashMap<>();

    /**
     * Another session of it (another load, owner or track): live frames and tracks not prepared ahead are dropped,
     * prepared ones kept (a playlist's next becomes its track), and the delay starts over.
     */
    void restart() {
        tracks.entrySet().removeIf(entry -> !entry.getValue().prepared());
        tracks.values().forEach(AnalysisTimeline::clearLive);
        delay = AnalysisDelay.INITIAL;
    }

    /** It plays {@code track} with {@code next} after it: the timelines of any other go. */
    void follow(String track, String next) {
        this.track = track;
        this.next = next;
        tracks.keySet().removeIf(each -> !each.equals(track) && !each.equals(next));
    }

    /** Whether {@code track} is its track or the next. */
    boolean has(String track) {
        return track.equals(this.track) || track.equals(next);
    }

    /** Its track, then the next. */
    List<String> both() {
        return List.of(track, next);
    }

    @Nullable
    AnalysisTimeline timeline() {
        return tracks.get(track);
    }

    AnalysisTimeline timeline(String track) {
        return tracks.computeIfAbsent(track, ignored -> new AnalysisTimeline());
    }

    /** How far behind {@code position} (ms into its track) live frames are played there. */
    long delay(long position) {
        return Math.round(delay.at(position));
    }

    /** Its signal {@code position} ms into its track; null if it has none there. */
    @Nullable
    AnalysisFrame sample(long position) {
        AnalysisTimeline timeline = timeline();
        return timeline == null ? null : timeline.sample(position, delay(position));
    }

    /** The lights' motion phase {@code position} ms into its track; NaN if it can't tell. */
    double phaseAt(long position) {
        AnalysisTimeline timeline = timeline();
        return timeline == null ? Double.NaN : timeline.phaseAt(position, delay(position));
    }
}
