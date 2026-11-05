package org.haruyq.createmediasystems.playback;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Set<Channel> CHANNELS = ConcurrentHashMap.newKeySet();

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
                if (!CHANNELS.isEmpty()) {
                    var engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).create_media_systems$soundEngine();
                    // updateStream only tops up what OpenAL has played, so an extra call costs nothing.
                    ((SoundEngineAccessor) engine).create_media_systems$executor().execute(() -> CHANNELS.forEach(Channel::updateStream));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
