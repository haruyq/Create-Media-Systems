package org.haruyq.createmediasystems.blocks.piano.client;

import java.util.Arrays;

/** Keeps key travel visible when a press and release arrive between two rendered frames. */
public final class KeyMotion {
    private static final long PRESS = 36_000_000, HOLD = 24_000_000, RELEASE = 75_000_000;
    private static final long UNSET = Long.MIN_VALUE;

    private final int first;
    private final long[] pressedAt, releasedAt;

    public KeyMotion(int first, int count) {
        this.first = first;
        pressedAt = new long[count];
        releasedAt = new long[count];
        Arrays.fill(pressedAt, UNSET);
        Arrays.fill(releasedAt, UNSET);
    }

    public synchronized void press(int note, long now) {
        int index = note - first;
        if (index < 0 || index >= pressedAt.length) return;
        if (pressedAt[index] == UNSET || releasedAt[index] != UNSET) {
            pressedAt[index] = now - Math.round(travelAt(index, now) * PRESS);
        }
        releasedAt[index] = UNSET;
    }

    public synchronized void release(int note, long now) {
        int index = note - first;
        if (index >= 0 && index < pressedAt.length && pressedAt[index] != UNSET && releasedAt[index] == UNSET) {
            releasedAt[index] = Math.max(now, pressedAt[index] + PRESS + HOLD);
        }
    }

    public synchronized float travel(int note, long now) {
        return travelAt(note - first, now);
    }

    private float travelAt(int index, long now) {
        long attack = pressedAt[index];
        if (attack == UNSET) return 0;
        long release = releasedAt[index];
        if (release != UNSET && now >= release) return Math.clamp(1 - (now - release) / (float) RELEASE, 0, 1);
        return Math.clamp((now - attack) / (float) PRESS, 0, 1);
    }

    public synchronized boolean moving(long now) {
        for (int i = 0; i < pressedAt.length; i++) {
            if (travelAt(i, now) > 0) return true;
        }
        return false;
    }
}
