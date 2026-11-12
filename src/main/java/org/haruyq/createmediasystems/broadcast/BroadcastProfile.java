package org.haruyq.createmediasystems.broadcast;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** Outdoor speech acoustics, with one block approximating one metre. */
public enum BroadcastProfile {
    STANDARD(0, 0, 0, 0, 1, 1, 1, 0, 0),
    HORN(1, 400, 8, 30, .24, .82, 96, 350, 3800),
    ARRAY(2, 800, 12, 7.5, .16, .72, 240, 380, 11_000),
    DIGITAL_HORN(3, 500, 10, 30, .22, .79, 128, 180, 6500);

    private static final double EDGE_FADE = .08;

    public static final StreamCodec<ByteBuf, BroadcastProfile> CODEC =
            ByteBufCodecs.idMapper(BroadcastProfile::byId, profile -> profile.id);

    private final int id;
    public final int reach;
    public final int energyPerTick;
    public final int highPassHz;
    public final int lowPassHz;
    private final double halfBeam, offAxis, below, reference;

    BroadcastProfile(int id, int reach, int energyPerTick, double halfBeam, double offAxis, double below,
            double reference, int highPassHz, int lowPassHz) {
        this.id = id;
        this.reach = reach;
        this.energyPerTick = energyPerTick;
        this.halfBeam = halfBeam;
        this.offAxis = offAxis;
        this.below = below;
        this.reference = reference;
        this.highPassHz = highPassHz;
        this.lowPassHz = lowPassHz;
    }

    public static BroadcastProfile byId(int id) {
        return switch (id) {
            case 1 -> HORN;
            case 2 -> ARRAY;
            case 3 -> DIGITAL_HORN;
            default -> STANDARD;
        };
    }

    public int id() {
        return id;
    }

    public boolean outdoor() {
        return this != STANDARD;
    }

    public double gain(Vec3 displacement) {
        if (!outdoor()) return 1;
        double distance = displacement.length();
        if (distance >= reach) return 0;
        if (distance < 1e-6) return 1;
        double horizontal = displacement.horizontalDistance();
        // Four cabinet faces overlap at the edges of their horizontal coverage.
        double azimuth = horizontal < 1e-6 ? 1
                : Math.max(Math.abs(displacement.x), Math.abs(displacement.z)) / horizontal;
        double elevation = Math.toDegrees(Math.atan2(displacement.y, horizontal));
        double beamOffset = (elevation + 4) / halfBeam;
        double beam = Math.exp(-.34657 * beamOffset * beamOffset);
        // Downward spill is diffuse, so it has no horizontal null directly below the pole.
        double downward = Math.max(0, -displacement.y / distance);
        double spill = offAxis + (below - offAxis) * downward * downward;
        double edge = Math.min(1, (reach - distance) / (reach * EDGE_FADE));
        double fade = edge * edge * (3 - 2 * edge);
        return (spill + (1 - spill) * azimuth * beam) * reference / Math.hypot(reference, distance) * fade;
    }
}
