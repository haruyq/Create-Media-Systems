package org.haruyq.createmediasystems.lighting;

import org.haruyq.createmediasystems.Rgb;
import org.haruyq.createmediasystems.api.lighting.AudioLevels;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The stage lights' signal at one moment, {@code millis} into a track: its sound's level, bands, pulse and tempo (if
 * {@code audio}), its picture's left and right colours (if {@code video}), and how far the lights' motion has run.
 * Worked out on a client (see LightingAnalyzer), shared through the server (see AnalysisCoordinator) and kept by each
 * console (see LightingConsoleBlock.Entity#receive).
 */
public record AnalysisFrame(long millis, float level, float bass, float mid, float high, float pulse, float bpm,
                            int left, int right, boolean audio, boolean video, double motionPhase) implements AudioLevels {
    public static final int INTERVAL = 100;
    public static final int MAX_FRAMES = 72_001;
    /** Longest track analysed ahead (see CachedLightingAnalysis). */
    public static final long MAX_DURATION = 7_200_000;
    /** Furthest into a track (a week) a position is believed: past it, a clock or packet is wrong. */
    public static final long MAX_MILLIS = 604_800_000;
    public static final float MIN_BPM = 30;
    public static final float MAX_BPM = 240;
    /** The beat a console shows with no audio. */
    public static final float DEFAULT_BPM = 120;
    public static final StreamCodec<ByteBuf, AnalysisFrame> CODEC = StreamCodec.of((buf, frame) -> {
        buf.writeLong(frame.millis).writeFloat(frame.level).writeFloat(frame.bass).writeFloat(frame.mid)
                .writeFloat(frame.high).writeFloat(frame.pulse).writeFloat(frame.bpm).writeInt(frame.left)
                .writeInt(frame.right).writeBoolean(frame.audio).writeBoolean(frame.video).writeDouble(frame.motionPhase);
    }, buf -> new AnalysisFrame(buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readDouble()));

    public AnalysisFrame(long millis, float level, float bass, float mid, float high, float pulse, float bpm,
                         int left, int right, boolean audio, boolean video) {
        this(millis, level, bass, mid, high, pulse, bpm, left, right, audio, video, millis / 1000.0);
    }

    public double motionSpeed() {
        return Math.clamp(0.35 + level * 0.65 + pulse * 0.3, 0.2, 1.2);
    }

    public AnalysisFrame phase(double phase) {
        return new AnalysisFrame(millis, level, bass, mid, high, pulse, bpm, left, right, audio, video, phase);
    }

    public boolean valid() {
        return millis >= 0 && millis <= MAX_MILLIS && unit(level) && unit(bass) && unit(mid) && unit(high)
                && unit(pulse) && Float.isFinite(bpm) && bpm >= MIN_BPM && bpm <= MAX_BPM
                && Double.isFinite(motionPhase) && Math.abs(motionPhase) <= 1_000_000_000
                && (left & 0xFF000000) == 0 && (right & 0xFF000000) == 0;
    }

    private static boolean unit(float value) {
        return Float.isFinite(value) && value >= 0 && value <= 1;
    }

    public AnalysisFrame at(long time) {
        return new AnalysisFrame(time, level, bass, mid, high, pulse, bpm, left, right, audio, video,
                motionPhase + Math.max(0, time - millis) / 1000.0 * motionSpeed());
    }

    public AnalysisFrame blend(AnalysisFrame next, long time) {
        float amount = Math.clamp((float) (time - millis) / Math.max(1, next.millis - millis), 0, 1);
        return new AnalysisFrame(time, mix(level, next.level, amount), mix(bass, next.bass, amount),
                mix(mid, next.mid, amount), mix(high, next.high, amount), mix(pulse, next.pulse, amount),
                mix(bpm, next.bpm, amount), Rgb.mix(left, next.left, amount), Rgb.mix(right, next.right, amount),
                audio, video, motionPhase + (next.motionPhase - motionPhase) * amount);
    }

    private static float mix(float a, float b, float amount) {
        return a + (b - a) * amount;
    }
}
