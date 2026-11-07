package org.haruyq.createmediasystems.lighting;

final class LightingMotion {
    private double time = Double.NaN;
    private double phaseTime = Double.NaN;
    private double phase;
    private double velocity;
    private float pan;
    private float tilt;

    static double seconds(long ticks, float partialTick) {
        return (ticks + (double) partialTick) / 20;
    }

    double phase(double seconds, float speed, float strength) {
        double desired = speed * (double) strength;
        if (!Double.isFinite(phaseTime)) {
            phase = seconds * speed;
            velocity = desired;
        } else if (seconds > phaseTime) {
            double elapsed = Math.min(seconds - phaseTime, 0.5);
            double weight = 1 - Math.exp(-elapsed * 6);
            phase += desired * elapsed + (velocity - desired) * weight / 6;
            velocity += (desired - velocity) * weight;
        }
        phaseTime = seconds;
        return phase;
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
