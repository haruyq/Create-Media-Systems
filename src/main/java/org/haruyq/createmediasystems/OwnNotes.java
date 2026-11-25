package org.haruyq.createmediasystems;

import java.util.ArrayDeque;

/**
 * Notes a client already played itself, so the server's echo of each is skipped once. Entries outlive a slow
 * round trip but expire, so a strike the server rejected cannot swallow someone else's note for long.
 */
public final class OwnNotes {
    static final long WINDOW = 2_000_000_000L;
    private record Note(Object key, long time) {}
    private final ArrayDeque<Note> notes = new ArrayDeque<>();

    public void add(Object key, long now) {
        if (notes.size() >= 64) notes.removeFirst();
        notes.addLast(new Note(key, now));
    }

    /** True when this is the echo of a note added here, which it then consumes. */
    public boolean echo(Object key, long now) {
        while (!notes.isEmpty() && now - notes.peekFirst().time > WINDOW) notes.removeFirst();
        for (var it = notes.iterator(); it.hasNext();) {
            if (it.next().key.equals(key)) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
