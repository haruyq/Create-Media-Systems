package org.haruyq.createmediasystems.api.lighting;

import org.jetbrains.annotations.ApiStatus;

/** A setting a lighting console keeps, for {@link ConsoleView#set}. May gain constants. */
@ApiStatus.Experimental
public enum ConsoleSetting {
    GROUP, BRIGHTNESS, MASTER, COLOR, SLOT, COUNT, RGB, MAPPING, VIDEO, AUDIO, BAND, MOTION, PATTERN, RANGE, SPREAD,
    SPEED, AIM, ENABLED, CYCLE, TARGET, PRESET, SOURCES, RANGE_ALL
}
