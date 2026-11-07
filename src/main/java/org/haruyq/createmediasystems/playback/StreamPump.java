package org.haruyq.createmediasystems.playback;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.haruyq.createmediasystems.mixin.SoundEngineAccessor;
import org.haruyq.createmediasystems.mixin.SoundManagerAccessor;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.Minecraft;

/**
 * Refills the OpenAL buffers of CMS's streams on the sound thread every {@link #PERIOD_MILLIS}. Vanilla refills
 * them once per client tick, and ticks run in the render loop, so at low FPS a frame longer than what a stream keeps
 * queued (about 0.4 s of a native player's, see VlcAudio) ran its source dry: the sound crackled as the engine dropped
 * and restarted it.
 */
public final class StreamPump {
    private static final long PERIOD_MILLIS = 20;
    /** How long a queued pass may wait before another is queued anyway: the engine drops its queue as it reloads. */
    private static final long STALE_NANOS = 1_000_000_000L;
    private static final Set<Channel> CHANNELS = ConcurrentHashMap.newKeySet();
    /** When the pass waiting on the sound thread was queued (System.nanoTime); 0 for none. */
    private static final AtomicLong POSTED_AT = new AtomicLong();

    static {
        Thread.ofPlatform().daemon().name("CMS stream pump").start(StreamPump::run);
    }

    private StreamPump() {
    }

    /** {@code channel} plays a CMS stream; until it is destroyed. */
    public static void add(Channel channel) {
        CHANNELS.add(channel);
    }

    public static void remove(Channel channel) {
        CHANNELS.remove(channel);
    }

    private static void run() {
        try {
            while (true) {
                Thread.sleep(PERIOD_MILLIS);
                long posted = POSTED_AT.get();
                // One pass queued at a time: a pass that takes longer than the period, queued every period anyway,
                // piled up on the sound thread without end, and every sound the game played (each waits on that
                // thread) froze it a while longer. Past STALE_NANOS it is taken as dropped (the engine reloading).
                if (!CHANNELS.isEmpty() && (posted == 0 || System.nanoTime() - posted >= STALE_NANOS)) {
                    var engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).create_media_systems$soundEngine();
                    POSTED_AT.set(System.nanoTime());
                    // updateStream only tops up what OpenAL has played, so an extra call costs nothing.
                    ((SoundEngineAccessor) engine).create_media_systems$executor().execute(() -> {
                        try {
                            CHANNELS.forEach(Channel::updateStream);
                        } finally {
                            POSTED_AT.set(0);
                        }
                    });
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
