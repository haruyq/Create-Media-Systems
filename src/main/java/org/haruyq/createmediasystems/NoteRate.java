package org.haruyq.createmediasystems;

/** Limits one player's notes to a burst of 32, refilled at three a tick. */
public class NoteRate {
    private static final int BURST = 32;
    private int tokens = BURST;
    private int tick;

    public boolean take(int now) {
        tokens = Math.min(BURST, tokens + 3 * Math.clamp(now - tick, 0, BURST));
        tick = now;
        if (tokens == 0) return false;
        tokens--;
        return true;
    }
}
