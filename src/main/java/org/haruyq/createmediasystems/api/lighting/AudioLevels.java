package org.haruyq.createmediasystems.api.lighting;

import org.jetbrains.annotations.ApiStatus;

/** The lights' signal at one moment of a track: its sound's levels (if {@link #audio}), its picture's colours (if {@link #video}). */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface AudioLevels {
    /** How far into the track, in milliseconds. */
    long millis();

    boolean audio();

    boolean video();

    float level();

    float bass();

    float mid();

    float high();

    /** The beat's pulse. */
    float pulse();

    /** Beats a minute. */
    float bpm();

    /** The picture's left and right colours, 0xRRGGBB. */
    int left();

    int right();

    /** How far the lights' motion has run. */
    double motionPhase();
}
