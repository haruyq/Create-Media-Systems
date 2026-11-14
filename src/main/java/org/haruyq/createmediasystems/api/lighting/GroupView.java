package org.haruyq.createmediasystems.api.lighting;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One of a lighting console's six fixture groups' settings. Enums may gain constants: switch with a default. */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface GroupView {
    /**
     * RANDOM: on each beat its lights point somewhere new at random, all the same way; those lit at random (see
     * chase) are then neighbours, a run of them somewhere along the group. CROSS: a sweep, every other light going
     * the other way, so their beams cross.
     */
    enum Motion { FIXED, SWEEP, CIRCLE, TRACK, RANDOM, CROSS }

    /** LASER_TUNNEL: a laser's rays in a ring, a cone of light as wide as the spread. */
    enum Pattern { SPOT, BEAM, LASER_RAY, LASER_FAN, LASER_SHEET, WASH, LASER_TUNNEL }

    enum Mapping { SEQUENCE, SPLIT, GRADIENT, PER_FIXTURE }

    enum Band { ALL, BASS, MID, HIGH }

    /** Its colour in {@code slot} (0 to 7), 0xRRGGBB. */
    int color(int slot);

    /** How many of its slots it uses. */
    int colorCount();

    /** Whether it shows its video source's colours now, a video being seen ({@code video}). */
    boolean videoColors(boolean video);

    boolean colorCycle();

    /** Whether it follows the sound, and which band of it. */
    boolean audio();

    Band audioBand();

    Motion motion();

    Pattern pattern();

    Mapping mapping();

    /** 0 to 1. */
    float brightness();

    /** Blocks. */
    float range();

    /** Degrees. */
    float spread();

    float speed();

    /** Degrees. */
    float pan();

    /** Degrees. */
    float tilt();

    /** How many times a second its lights flash; 0 for a steady light. */
    float strobe();

    /**
     * How many of its lights are lit at once, chosen again at random on each beat; 0 for all of them. Any of them,
     * or under Motion.RANDOM a run of neighbours.
     */
    int chase();

    /**
     * Whether those lit (see chase) are a run of neighbours that steps one light along the group on each beat,
     * rather than chosen at random.
     */
    boolean chaseStep();

    /** The player its lights follow (Motion.TRACK); null for none. */
    @Nullable
    UUID target();
}
