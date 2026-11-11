package org.haruyq.createmediasystems.stream;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;

/**
 * Where a source is in its track, on the server's game time: how far in it was ({@code offset}) when it began counting
 * ({@code start}), and whether it runs. Every source plays on one. A block source's stands while its listeners load
 * its track (see Streams#loading and Streams#tick) and counts only the ticks its block is ticked; a portable one's (a
 * launched disc, a PartyBox, a megaphone) runs on game time wherever it is, and its clients read it the same way.
 * Each change bumps its revision, which a portable's clients take as a new playback.
 */
public final class PlaybackClock {
    public static final int TICKS_PER_SECOND = 20;
    /** One tick on a client's clock (System.nanoTime), for its copies of the server's waits. */
    public static final long NANOS_PER_TICK = 1_000_000_000L / TICKS_PER_SECOND;
    /** {@code start} while it doesn't count (stopped, paused, or standing), and {@code last} before its first tick. */
    static final long NEVER = Long.MIN_VALUE;

    private final boolean unattended;
    private long offset;
    private long start = NEVER;
    /** The game time of its last tick (block sources): what it saves is where it was then. */
    private long last = NEVER;
    private boolean running;
    private int revision;
    /** Its track's length in seconds, once learned (see learn); not above 0 while unknown. */
    private double length = -1;

    public PlaybackClock() {
        this(false);
    }

    private PlaybackClock(boolean unattended) {
        this.unattended = unattended;
    }

    /**
     * A block source's clock whose track plays on after a moment with nobody there to load it (see Streams#loading),
     * instead of waiting for someone to come, as a deck's counter runs with nobody in the room.
     */
    public static PlaybackClock unattended() {
        return new PlaybackClock(true);
    }

    /** A portable's clock as its owner keeps it: {@code start} is the game time it counts from while it runs. */
    public static PlaybackClock of(long offset, long start, boolean running, int revision, double length) {
        PlaybackClock clock = new PlaybackClock();
        clock.offset = Math.max(0, offset);
        clock.start = start;
        clock.running = running;
        clock.revision = revision;
        clock.length = length;
        return clock;
    }

    /** A portable's clock as {@link #save} wrote it. */
    public static PlaybackClock read(CompoundTag tag) {
        return of(tag.getLong("Offset"), tag.getLong("Start"), tag.getBoolean("Playing"), tag.getInt("Revision"),
                tag.getDouble("Duration"));
    }

    public void save(CompoundTag tag) {
        tag.putLong("Offset", offset);
        tag.putLong("Start", start);
        tag.putBoolean("Playing", running);
        tag.putInt("Revision", revision);
        tag.putDouble("Duration", length);
    }

    /** How far into its track it is at game time {@code now} (ticks). */
    public long position(long now) {
        return running && start != NEVER ? offset + Math.max(0, now - start) : offset;
    }

    public double seconds(long now) {
        return position(now) / (double) TICKS_PER_SECOND;
    }

    /** Where it was at its last tick: what a block source saves (see load). */
    public long position() {
        return last == NEVER ? offset : position(last);
    }

    public boolean running() {
        return running;
    }

    public int revision() {
        return revision;
    }

    public long offset() {
        return offset;
    }

    /** The game time it counts from; meaningful only while it runs. */
    public long start() {
        return start;
    }

    public double length() {
        return length;
    }

    boolean playsUnattended() {
        return unattended;
    }

    /** A new run from {@code at} ticks in: it stands there until it counts on (see tick). */
    public void start(long at) {
        offset = Math.max(0, at);
        start = NEVER;
        running = true;
        revision++;
    }

    /** Goes to {@code at} ticks in, running or not; running, it stands there until it counts on (see tick). */
    public void seek(long at) {
        offset = Math.max(0, at);
        start = NEVER;
        revision++;
    }

    /** A portable seek reanchors a running clock immediately, including across an unloaded interval. */
    public void seek(long at, long now) {
        seek(at);
        if (running) start = now;
    }

    /** A block source's clock runs on from where it is, from its next tick (see tick), or once its listeners have it. */
    public void resume() {
        if (!running) {
            running = true;
            start = NEVER;
            revision++;
        }
    }

    /** A portable's clock runs on from where it is, from {@code now}. */
    public void play(long now) {
        if (!running) {
            running = true;
            start = now;
            revision++;
        }
    }

    public void pause(long now) {
        if (running) {
            stopAt(position(now));
        }
    }

    private void stopAt(long at) {
        offset = Math.max(0, at);
        start = NEVER;
        running = false;
        revision++;
    }

    public void stop() {
        stopAt(0);
    }

    /** Stands where it is at {@code now}, running or not, until it counts on (see tick). */
    void hold(long now) {
        offset = position(now);
        start = NEVER;
    }

    /**
     * One tick at {@code now} of a block source's clock that nobody waits on (see Streams#tick for one its listeners
     * load): standing, it counts on from now. Ticks its block missed while loaded don't count, as those it missed
     * unloaded don't (see load).
     */
    public void tick(long now) {
        tick(now, true);
    }

    /** The same; while {@code counts} is false it stands: its listeners still load what it plays. */
    public void tick(long now, boolean counts) {
        if (running && start != NEVER && last != NEVER && now > last + 1) {
            start += now - last - 1;
        }
        last = now;
        if (!running) {
            return;
        }
        if (!counts) {
            hold(now);
        } else if (start == NEVER) {
            start = now;
        }
    }

    /** A block source's clock as it saved {@link #position()}: it stands there, and counts on from its next tick. */
    public void load(long at, boolean running) {
        offset = Math.max(0, at);
        start = NEVER;
        last = NEVER;
        this.running = running;
    }

    /**
     * Whether it runs past the length of {@code url} as the API or a listener told it (see Streams#endedAt), asked as
     * emitters relay, every Streams#RELAY_TICKS: a block source then moves on.
     */
    public boolean ended(String url, long now) {
        return running && now % Streams.RELAY_TICKS == 0 && Streams.endedAt(url, position(now));
    }

    /** Whether it has played past its track's length, once learned: a portable then stops. */
    public boolean ended(long now) {
        return length > 0 && position(now) >= length * TICKS_PER_SECOND;
    }

    /** Takes {@code seconds} as its track's length while it knows none (the server's lookup or a listener's). */
    public void learn(double seconds) {
        if (length <= 0 && MediaItem.plausibleLength(seconds)) {
            length = seconds;
        }
    }

    public void forget() {
        length = -1;
    }

    public static String format(double seconds) {
        long s = Math.max(0, (long) seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * What a battery-powered clock (a PartyBox's, a megaphone's) has spent: when it last settled, and the part of an
     * energy unit it owes.
     */
    public static final class Drain {
        private long settledAt;
        private double owed;

        public Drain(long settledAt, double owed) {
            this.settledAt = settledAt;
            this.owed = Math.clamp(owed, 0, .999999);
        }

        /** As {@link #save} wrote it, the part owed under {@code owedKey}; settled {@code now} if never. */
        public static Drain read(CompoundTag tag, String owedKey, long now) {
            return new Drain(tag.contains("PowerAt") ? tag.getLong("PowerAt") : now, tag.getDouble(owedKey));
        }

        public void save(CompoundTag tag, String owedKey) {
            tag.putLong("PowerAt", settledAt);
            tag.putDouble(owedKey, owed);
        }

        /**
         * Pays {@code rate} of {@code energy} for every tick {@code clock} played since it last settled, up to its
         * track's end once that is known. Where the energy runs out, the clock stops at the last tick paid for, so time
         * away (an unloaded chunk, a chest) is charged in full. Returns the energy left.
         */
        public int settle(PlaybackClock clock, long now, int energy, double rate) {
            long elapsed = settledAt == NEVER ? 0 : Math.max(0, now - settledAt);
            settledAt = Math.max(settledAt, now);
            if (!clock.running()) {
                return energy;
            }
            long current = clock.position(now), previous = Math.max(0, current - elapsed);
            long end = clock.length > 0 ? (long) Math.ceil(clock.length * TICKS_PER_SECOND) : Long.MAX_VALUE;
            long paid = current >= end ? Math.min(elapsed, Math.max(0, end - previous)) : elapsed;
            double spent = owed + paid * rate;
            if (spent >= energy) {
                clock.stopAt(previous + Math.max(0, (long) Math.floor((energy - owed) / rate)));
                owed = 0;
                return 0;
            }
            int whole = (int) spent;
            owed = spent - whole;
            return energy - whole;
        }
    }
}
