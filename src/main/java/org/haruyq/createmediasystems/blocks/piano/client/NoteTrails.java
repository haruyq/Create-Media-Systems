package org.haruyq.createmediasystems.blocks.piano.client;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.blocks.keyboard.a88mkii.Layout;

public final class NoteTrails {
    public static final long TRAVEL = 1_850_000_000L;
    private static final long MIN_LENGTH = 85_000_000L;
    private static final int MAX_RECENT_RELEASES = 1024;
    private static final Comparator<Span> ORDER = Comparator.comparingInt(Span::note).thenComparingInt(Span::part);
    private final Map<Integer, Map<Integer, Span>> held = new HashMap<>();
    private final ArrayDeque<Span> released = new ArrayDeque<>();

    record Span(int note, int part, int color, int velocity, long start, long end) {}

    public record Trail(int note, int color, float velocity, float head, float tail, int lane, int lanes) {}

    public synchronized void press(int part, int color, int note, int velocity, long now) {
        if (note < Layout.LOWEST || note > Layout.HIGHEST) return;
        var keys = held.computeIfAbsent(part, id -> new HashMap<>());
        if (!keys.containsKey(note)) keys.put(note, new Span(note, part, color(color), velocity, now, 0));
    }

    public synchronized void release(int part, int note, long now) {
        var keys = held.get(part);
        if (keys == null) return;
        var span = keys.remove(note);
        if (span != null) finish(span, now);
        if (keys.isEmpty()) held.remove(part);
    }

    public synchronized void release(int part, long now) {
        var keys = held.remove(part);
        if (keys != null) keys.values().forEach(span -> finish(span, now));
    }

    private void finish(Span span, long now) {
        released.addLast(new Span(span.note, span.part, span.color, span.velocity, span.start, now));
        while (released.size() > MAX_RECENT_RELEASES) released.removeFirst();
    }

    public synchronized List<Trail> visible(long now) {
        released.removeIf(span -> now - Math.max(span.end, span.start + MIN_LENGTH) >= TRAVEL);
        if (held.isEmpty() && released.isEmpty()) return List.of();
        var active = new ArrayList<>(released);
        held.values().forEach(keys -> active.addAll(keys.values()));
        active.sort(ORDER);
        var trails = new ArrayList<Trail>(active.size());
        for (int first = 0; first < active.size();) {
            int last = first + 1, lanes = 1;
            while (last < active.size() && active.get(last).note == active.get(first).note) {
                if (active.get(last).part != active.get(last - 1).part) lanes++;
                last++;
            }
            int lane = 0;
            for (int i = first; i < last; i++) {
                var span = active.get(i);
                if (i > first && span.part != active.get(i - 1).part) lane++;
                float head = Math.clamp((now - span.start) / (float) TRAVEL, 0, 1);
                float tail = span.end == 0 ? 0 : Math.max(0, (now - Math.max(span.end, span.start + MIN_LENGTH)) / (float) TRAVEL);
                trails.add(new Trail(span.note, span.color, Math.clamp(span.velocity / 127f, .24f, 1), head, tail, lane, lanes));
            }
            first = last;
        }
        return trails;
    }

    public static int color(int part) {
        if (part == 0) return 0xADB9D9;
        float hue = (float) ((.531 + (part - 1) * .3819660112501051) % 1.0);
        return Color.HSBtoRGB(hue, .72f, 1) & 0xFFFFFF;
    }
}
