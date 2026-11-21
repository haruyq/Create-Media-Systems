package org.haruyq.createmediasystems.items.instrument;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The two-channel amp on a guitar. Drive uses the same 0–127 units as its MIDI controller. */
public record GuitarAmp(boolean distortion, int drive) {
    public static final GuitarAmp DEFAULT = new GuitarAmp(false, 64);
    static final Codec<GuitarAmp> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("distortion").forGetter(GuitarAmp::distortion),
            Codec.intRange(0, 127).fieldOf("drive").forGetter(GuitarAmp::drive)
    ).apply(instance, GuitarAmp::new));
}
