package org.haruyq.createmediasystems.playback;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Physical (non-cosmetic) Doppler pitch for a moving musical source.
 * A Minecraft block is treated as a metre; velocities are blocks/tick, converted
 * to blocks/second. The same musical OpenAL source changes pitch and location.
 * No separate fly-by effect, synthetic whistle, or fake second audio emitter.
 */
public final class DopplerMath {
    public static final double SPEED_OF_SOUND = 343.0;
    public static final double TICKS_PER_SECOND = 20.0;
    public static final double MIN_PITCH = 0.65;
    public static final double MAX_PITCH = 1.60;
    /** The longest step (blocks/tick) taken for motion: longer is a spawn, a teleport or a collision. */
    private static final double MAX_STEP = 2;

    private DopplerMath() {}

    /** How far {@code entity} moved in its last client tick, as a velocity; null for a jump (see {@link #steady}). */
    @Nullable
    public static Vec3 step(Entity entity) {
        return steady(new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo));
    }

    /**
     * A position step (blocks/tick) as a velocity: client steps include collision and interpolation, so a spawn or
     * teleport discontinuity is none (null).
     */
    @Nullable
    static Vec3 steady(Vec3 step) {
        double length = step.lengthSqr();
        return Double.isFinite(length) && length <= MAX_STEP * MAX_STEP ? step : null;
    }

    /** All velocities are blocks PER TICK. Returns a bounded pitch ratio, not a frequency in Hz. */
    public static double ratio(Vec3 source, Vec3 sourceVelocity, Vec3 listener, Vec3 listenerVelocity) {
        Vec3 toListener = listener.subtract(source);
        double distanceSquared = toListener.lengthSqr();
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0025) return 1.0;
        Vec3 unit = toListener.scale(1.0 / Math.sqrt(distanceSquared));
        // The unit vector goes from source -> listener; a positive source radial
        // component means approaching. A positive listener radial component means receding.
        double sourceRadial = sourceVelocity.dot(unit) * TICKS_PER_SECOND;
        double listenerRadial = listenerVelocity.dot(unit) * TICKS_PER_SECOND;
        if (!Double.isFinite(sourceRadial) || !Double.isFinite(listenerRadial)) return 1.0;
        // Prevent division through zero in pathological vehicles / teleports.
        sourceRadial = clamp(sourceRadial, -0.9 * SPEED_OF_SOUND, 0.9 * SPEED_OF_SOUND);
        listenerRadial = clamp(listenerRadial, -0.9 * SPEED_OF_SOUND, 0.9 * SPEED_OF_SOUND);
        double pitch = (SPEED_OF_SOUND - listenerRadial) / (SPEED_OF_SOUND - sourceRadial);
        return clamp(pitch, MIN_PITCH, MAX_PITCH);
    }

    /** Smooths packet jitter in the actual music source, without postponing the change indefinitely. */
    public static float smooth(float previous, double target) {
        if (!Double.isFinite(target)) target = 1.0;
        float next = (float) (previous + 0.38 * (clamp(target, MIN_PITCH, MAX_PITCH) - previous));
        return (float) clamp(next, MIN_PITCH, MAX_PITCH);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
