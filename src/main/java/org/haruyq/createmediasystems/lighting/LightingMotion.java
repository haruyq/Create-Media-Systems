package org.haruyq.createmediasystems.lighting;

final class LightingMotion {
    private double time = Double.NaN;
    private float pan;
    private float tilt;

    static double seconds(long ticks, float partialTick) {
        return (ticks + (double) partialTick) / 20;
    }

    Angles update(double seconds, float desiredPan, float desiredTilt, boolean hold) {
        if (!Double.isFinite(time) || seconds < time) {
            pan = desiredPan;
            tilt = desiredTilt;
        } else if (!hold) {
            double weight = 1 - Math.exp(-Math.min(seconds - time, 0.5) * 9);
            float delta = (desiredPan - pan) % 360;
            if (delta >= 180) {
                delta -= 360;
            } else if (delta < -180) {
                delta += 360;
            }
            pan += delta * weight;
            tilt += (desiredTilt - tilt) * weight;
        }
        time = seconds;
        return new Angles(pan, tilt);
    }

    record Angles(float pan, float tilt) {
    }
}
