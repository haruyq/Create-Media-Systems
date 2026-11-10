package org.haruyq.createmediasystems.launcher;

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

    private DopplerMath() {}

    /** Client position steps include collision and interpolation; ignore spawn/teleport discontinuities. */
    public static double observedRatio(
            double sourceX, double sourceY, double sourceZ,
            double sourceVx, double sourceVy, double sourceVz,
            double listenerX, double listenerY, double listenerZ,
            double listenerVx, double listenerVy, double listenerVz) {
        double sourceStep = sourceVx * sourceVx + sourceVy * sourceVy + sourceVz * sourceVz;
        double listenerStep = listenerVx * listenerVx + listenerVy * listenerVy + listenerVz * listenerVz;
        if (!Double.isFinite(sourceStep) || !Double.isFinite(listenerStep)
                || sourceStep > 4 || listenerStep > 4) return 1.0;
        return ratio(sourceX, sourceY, sourceZ, sourceVx, sourceVy, sourceVz,
                listenerX, listenerY, listenerZ, listenerVx, listenerVy, listenerVz);
    }

    /** All velocities are blocks PER TICK. Returns a bounded pitch ratio, not a frequency in Hz. */
    public static double ratio(
            double sourceX, double sourceY, double sourceZ,
            double sourceVx, double sourceVy, double sourceVz,
            double listenerX, double listenerY, double listenerZ,
            double listenerVx, double listenerVy, double listenerVz) {
        double dx = listenerX - sourceX;
        double dy = listenerY - sourceY;
        double dz = listenerZ - sourceZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0025) return 1.0;
        double inverseDistance = 1.0 / Math.sqrt(distanceSquared);
        double ux = dx * inverseDistance;
        double uy = dy * inverseDistance;
        double uz = dz * inverseDistance;
        // The unit vector goes from source -> listener; a positive source radial
        // component means approaching. A positive listener radial component means receding.
        double sourceRadial = (sourceVx * ux + sourceVy * uy + sourceVz * uz) * TICKS_PER_SECOND;
        double listenerRadial = (listenerVx * ux + listenerVy * uy + listenerVz * uz) * TICKS_PER_SECOND;
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
