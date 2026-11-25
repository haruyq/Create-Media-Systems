package org.haruyq.createmediasystems.blocks.steelpan.client;

import java.util.ArrayList;
import java.util.List;
import org.haruyq.createmediasystems.blocks.steelpan.SteelpanLayout;

/** Bounded, render-thread visual timeline, independent of audio and game tick rate. */
public final class SteelpanState {
    public record Stroke(int midi, float velocity, int hand, long impact) {}
    public record Mallet(int from, int to, float progress, float lift, float activity) {}
    private final List<Stroke> strokes = new ArrayList<>();
    static final long PREPARE = 85_000_000L;
    private static final long REBOUND = 8_000_000L;
    private static final long MIN_STROKE = 12_000_000L;
    private int nextHand;

    /** Schedule before impact for a full preparation stroke. False means invalid input or an older hand event. */
    public boolean strikeAt(int midi, float velocity, int hand, long impactNanos) {
        if (!SteelpanLayout.contains(midi) || !Float.isFinite(velocity) || velocity <= 0 || hand < 0 || hand > 1) return false;
        for (var s : strokes) if (s.hand() == hand && s.impact() >= impactNanos) return false;
        strokes.add(new Stroke(midi, Math.min(1, velocity), hand, impactNanos));
        if (strokes.size() > 64) strokes.removeFirst();
        return true;
    }

    /** Convenience for live visual inputs. Impact occurs 85 ms later so the mallet can reach the field. */
    public long strike(int midi, float velocity, long now) {
        long impact = now + PREPARE;
        if (strikeAt(midi, velocity, nextHand, impact)) nextHand ^= 1;
        return impact;
    }

    /** The mallet whose last stroke is oldest, so own strikes alternate and seldom wait on a stroke in flight. */
    int freeHand() {
        long[] last = {Long.MIN_VALUE, Long.MIN_VALUE};
        for (var s : strokes) last[s.hand()] = Math.max(last[s.hand()], s.impact());
        return last[1] < last[0] ? 1 : 0;
    }

    long schedule(int midi, float velocity, int hand, long now) {
        long impact = now + PREPARE;
        for (var stroke : strokes) {
            if (stroke.hand() == hand) impact = Math.max(impact, stroke.impact() + MIN_STROKE);
        }
        strikeAt(midi, velocity, hand, impact);
        return impact;
    }

    /** A deliberately slowed visual mode, not an acoustic-frequency simulation. Field boundaries stay fixed. */
    public float vibration(int midi, long now) {
        if (!SteelpanLayout.contains(midi)) return 0;
        double response = 0;
        for (var s : strokes) {
            double age = (now - s.impact()) * 1e-9;
            if (s.midi() != midi || age < 0 || age > .6) continue;
            response += .0006 * s.velocity() * Math.exp(-age / .085) * Math.sin(age * Math.PI * 2 * (22 + (midi-60)*.13));
        }
        return (float) Math.clamp(response, -.0008, .0008);
    }

    public float sway(long now) {
        double angle = 0;
        for (var s : strokes) {
            double age = (now - s.impact()) * 1e-9;
            if (age >= 0 && age < .8) angle += .045 * s.velocity() * Math.exp(-age / .16) * Math.sin(age * Math.PI * 14);
        }
        return (float) Math.clamp(angle, -.09, .09);
    }

    public Mallet mallet(int hand, long now) {
        if (hand < 0 || hand > 1) throw new IllegalArgumentException("Mallet hand must be 0 or 1");
        Stroke previous = null, upcoming = null;
        for (var s : strokes) if (s.hand() == hand) {
            if (s.impact() <= now && (previous == null || s.impact() > previous.impact())) previous = s;
            if (s.impact() > now && (upcoming == null || s.impact() < upcoming.impact())) upcoming = s;
        }
        int rest = hand == 0 ? 60 : 67;
        if (upcoming != null) {
            long begin = upcoming.impact() - PREPARE;
            if (previous != null) begin = Math.max(begin, previous.impact() + REBOUND);
            if (now >= begin && upcoming.impact() > begin) {
                float t = (float) ((double) (now - begin) / (upcoming.impact() - begin));
                float smooth = t*t*(3-2*t);
                // Capture the previous rebound's exact height at the handover: no vertical teleport on a fast roll.
                float startLift = previous == null ? .12f : rebound((begin - previous.impact()) * 1e-9);
                float lift = startLift*(1-t) + .045f*(float) Math.sin(Math.PI*t);
                float before = previous == null ? 0 : activity((begin - previous.impact()) * 1e-9);
                float active = before + (1-before)*smooth(t*2);
                return new Mallet(previous == null ? rest : previous.midi(), upcoming.midi(), smooth, lift, active);
            }
        }
        if (previous == null) return new Mallet(rest, rest, 1, .12f, 0);
        double age = (now - previous.impact()) * 1e-9;
        return new Mallet(previous.midi(), previous.midi(), 1, rebound(age), activity(age));
    }

    private static float rebound(double seconds) {
        return .12f * (float) Math.sin(Math.PI/2 * Math.clamp(seconds/.085, 0, 1));
    }
    private static float activity(double age) { return age <= .55 ? 1 : 1-smooth((float)((age-.55)/.45)); }
    private static float smooth(float t) { t = Math.clamp(t, 0, 1); return t*t*(3-2*t); }
    public void reset() { strokes.clear(); nextHand = 0; }
    public int queuedStrokes() { return strokes.size(); }
}
