package org.haruyq.createmediasystems.playback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.stream.Streams;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * The taps of one playback here (a native player, or an API stream): each is offered what plays as it plays, but
 * only while something keeps asking for it, so a playback nobody follows is not analysed.
 */
public final class MediaTaps {
    /** A tap not asked for in this long (ns) is offered nothing more until it is again. */
    static final long DEMAND_NANOS = 2_000_000_000L;
    /** Sound starting further than this (µs) from where a tap's last ended is a seek or a gap: the tap starts over. */
    static final long GAP_MICROS = 50_000;

    private static final Map<BlockPos, LinkedHashSet<Relay>> RELAYS = new HashMap<>();

    /**
     * An API stream playing a source here (see StreamSound.PcmStream): the channel and track it plays, its taps, and
     * where in its track is heard.
     */
    public interface Relay {
        Streams.Channel channel();

        @Nullable
        String track();

        MediaTaps taps();

        /** Where in its track what is heard at {@code now} (System.nanoTime) is (s); -1 if nothing of it is. */
        double heardSeconds(long now);
    }

    private static final class Demand {
        final MediaTap tap;
        volatile long until;
        /** Where the sound it was last offered ends (µs); 0 for none. Guarded by the taps. */
        long end;

        Demand(MediaTap tap) {
            this.tap = tap;
        }

        boolean wanted(long now) {
            return now - until < 0;
        }
    }

    private final Map<Class<?>, Demand> taps = new ConcurrentHashMap<>();
    private final LongSupplier now;

    /** Taps of a playback whose clock reads {@code now} (µs), having offered every tap what is heard up to then. */
    public MediaTaps(LongSupplier now) {
        this.now = now;
    }

    /** Its tap of {@code kind}, made by {@code create} the first time; asking keeps it offered what plays a while. */
    public <T extends MediaTap> T get(Class<T> kind, Supplier<T> create) {
        Demand demand = taps.computeIfAbsent(kind, ignored -> new Demand(create.get()));
        demand.until = System.nanoTime() + DEMAND_NANOS;
        return kind.cast(demand.tap);
    }

    /** Now on the playback's clock (µs), what is heard up to it offered: what to ask a tap for what is heard now. */
    public long now() {
        return now.getAsLong();
    }

    /** See MediaTap#sound. */
    synchronized void sound(byte[] pcm, int count, int channels, long pts) {
        long nanos = System.nanoTime();
        for (Demand demand : taps.values()) {
            if (!demand.wanted(nanos)) {
                continue;
            }
            if (demand.end != 0 && Math.abs(pts - demand.end) > GAP_MICROS) {
                demand.tap.clear();
            }
            demand.tap.sound(pcm, count, channels, pts);
            demand.end = pts + (long) count * 1_000_000 / Pcm.RATE;
        }
    }

    /** A picture shown now (see MediaTap#picture); {@code pixel} is read only if a tap wants it. */
    void picture(int width, int height, IntUnaryOperator pixel) {
        long nanos = System.nanoTime();
        long at = 0;
        for (Demand demand : taps.values()) {
            if (demand.wanted(nanos)) {
                at = at == 0 ? now() : at;
                demand.tap.picture(width, height, pixel, at);
            }
        }
    }

    /** What played no longer goes on: every tap starts over. */
    synchronized void clear() {
        for (Demand demand : taps.values()) {
            demand.end = 0;
            demand.tap.clear();
        }
    }

    /** The API streams playing {@code source} here, oldest first. */
    public static List<Relay> relays(BlockPos source) {
        synchronized (RELAYS) {
            LinkedHashSet<Relay> playing = RELAYS.get(source);
            return playing == null ? List.of() : new ArrayList<>(playing);
        }
    }

    static void open(BlockPos source, Relay relay) {
        synchronized (RELAYS) {
            RELAYS.computeIfAbsent(source.immutable(), key -> new LinkedHashSet<>()).add(relay);
        }
    }

    static void close(BlockPos source, Relay relay) {
        synchronized (RELAYS) {
            LinkedHashSet<Relay> playing = RELAYS.get(source);
            if (playing != null && playing.remove(relay) && playing.isEmpty()) {
                RELAYS.remove(source);
            }
        }
    }
}
