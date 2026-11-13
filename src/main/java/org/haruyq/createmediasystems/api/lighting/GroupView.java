package org.haruyq.createmediasystems.api.lighting;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One of a lighting console's six fixture groups' settings. Enums may gain constants: switch with a default. */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface GroupView {
    enum Motion { FIXED, SWEEP, CIRCLE, TRACK }

    enum Pattern { SPOT, BEAM, LASER_RAY, LASER_FAN, LASER_SHEET, WASH }

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

    /** The player its lights follow (Motion.TRACK); null for none. */
    @Nullable
    UUID target();
}
