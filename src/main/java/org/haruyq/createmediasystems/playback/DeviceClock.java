package org.haruyq.createmediasystems.playback;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;

/**
 * OpenAL's own clock, which streams of one playback start on and line up against. A plain alSourcePlay starts at
 * OpenAL's next mix, up to 20 ms later, at its own moment for each sound, which split a stereo pair. All on the sound
 * engine's thread.
 */
public final class DeviceClock {
    /** Covers getting from lining a stream up to the engine starting it, so its start time is never already past. */
    static final long START_DELAY_NANOS = 100_000_000L;

    private DeviceClock() {
    }

    /** A stream that starts at a time of its own; see mixin/ChannelMixin. */
    public interface Timed {
        /**
         * Attached to the OpenAL {@code source}, at {@code deviceNow} (see {@link #now}); the device clock time to
         * start at, or 0 for at once.
         */
        long attach(int source, long deviceNow);

        default boolean advancesWhilePaused() {
            return false;
        }
    }

    /** OpenAL's clock now (ns), or 0 if it can't schedule starts, so everything plays at once as before. */
    public static long now() {
        long device = device();
        return device == 0 ? 0 : SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
    }

    /** OpenAL's clock now and how long until what it mixes then is heard (ns); null if it can't tell. */
    @Nullable
    static long[] nowAndLatency() {
        long device = device();
        if (device == 0) {
            return null;
        }
        long[] both = new long[2];
        SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_LATENCY_SOFT, both);
        return both;
    }

    /**
     * When audio queued on {@code source} now will start playing (device clock, ns): after the buffers of
     * {@code bufferSamples} each already queued, from where the source is now, or from {@code startAt} if it hasn't
     * started yet.
     */
    static long queueEnd(int source, long startAt, int bufferSamples) {
        return queueEnd(source, startAt, (long) AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) * bufferSamples);
    }

    private static long queueEnd(int source, long startAt, long queuedSamples) {
        long[] offsetAndClock = new long[2];
        SOFTSourceLatency.alGetSourcei64vSOFT(source, SOFTDeviceClock.AL_SAMPLE_OFFSET_CLOCK_SOFT, offsetAndClock);
        // The offset is 32.32 fixed point, from the start of the queue; 0 (and the clock now) until it plays.
        long queueStart = offsetAndClock[1] - (offsetAndClock[0] >> 32) * 1_000_000_000L / Pcm.RATE;
        return Math.max(queueStart, startAt) + queuedSamples * 1_000_000_000L / Pcm.RATE;
    }

    static int queuedBuffers(int source) {
        return AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
    }

    static long presentationTime(int source, long startAt, long queuedSamples) {
        long now = System.nanoTime();
        long[] device = nowAndLatency();
        if (device != null) {
            return now + Math.max(0, queueEnd(source, startAt, queuedSamples) - device[0]) + device[1];
        }
        double remaining = queuedSamples / (double) Pcm.RATE - AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET);
        return now + Math.max(0, Math.round(remaining * 1e9));
    }

    /** The open device if it has everything needed, else 0. */
    private static long device() {
        long context = ALC10.alcGetCurrentContext();
        if (context == 0) {
            return 0;
        }
        long device = ALC10.alcGetContextsDevice(context);
        var al = AL.getCapabilities();
        return al.AL_SOFT_source_start_delay && al.AL_SOFT_source_latency
                && ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock") ? device : 0;
    }
}
