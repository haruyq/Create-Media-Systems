package org.haruyq.createmediasystems.items.partybox.stage320;
import org.haruyq.createmediasystems.items.power.BatteryItem;

/** JBL's Stage 320 dimensions and rated figures; FE/range are explicit Minecraft adaptations. */
public final class PartyBoxSpecs {
    public static final float WIDTH = .335f;
    public static final long RATED_TICKS = 18L * 60 * 60 * 20;
    public static final double HEAR_RANGE = 64;
    private PartyBoxSpecs() {}
    public static double drainPerTick(int volume, int bass, int lights) {
        double level = Math.clamp(volume, 0, 32) / 18.0;
        return BatteryItem.CAPACITY / (double) RATED_TICKS * (.30 + .70 * level * level)
                * (1 + Math.clamp(bass, 0, 2) * .22 + (lights == 0 ? 0 : .12));
    }
    public static double desiredSpeed(double distance) { return Math.min(.32, Math.max(0, distance - .04) * .30); }
}
