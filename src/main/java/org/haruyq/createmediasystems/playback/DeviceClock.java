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

    /**
     * When audio queued on {@code source} now, after {@code queuedFrames} frames (at {@code rate} a second) already
     * there, will be heard (System.nanoTime): from where the source is now, or from {@code startAt} if it hasn't
     * started yet, at the source's pitch, plus the device's latency.
     */
    static long presentationTime(int source, long startAt, long queuedFrames, float rate) {
        long now = System.nanoTime();
        double pitch = Math.max(.01, AL10.alGetSourcef(source, AL10.AL_PITCH));
        double queued = queuedFrames / (double) rate;
        long[] device = nowAndLatency();
        if (device == null) {
            double remaining = (queued - AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET)) / pitch;
            return now + Math.max(0, Math.round(remaining * 1e9));
        }
        long[] offsetAndClock = new long[2];
        SOFTSourceLatency.alGetSourcei64vSOFT(source, SOFTDeviceClock.AL_SAMPLE_OFFSET_CLOCK_SOFT, offsetAndClock);
        // The offset is 32.32 fixed point, from the start of the queue; 0 (and the clock now) until it plays.
        long queueStart = offsetAndClock[1] - Math.round((offsetAndClock[0] >> 32) / (double) rate / pitch * 1e9);
        long end = Math.max(queueStart, startAt) + Math.round(queued / pitch * 1e9);
        return now + Math.max(0, end - device[0]) + device[1];
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
