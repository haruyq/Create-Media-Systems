package org.haruyq.createmediasystems.hifi;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Where a cassette was stopped, kept on the tape as on a real one: the track, and how far into it (ticks). A deck
 * plays it on from there, so it has to be rewound to be heard from the top.
 */
public record TapePosition(int track, int ticks) {
    public static final TapePosition START = new TapePosition(0, 0);
    public static final Codec<TapePosition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("track").forGetter(TapePosition::track),
            Codec.INT.fieldOf("ticks").forGetter(TapePosition::ticks)
    ).apply(i, TapePosition::new));
    public static final StreamCodec<ByteBuf, TapePosition> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TapePosition::track,
            ByteBufCodecs.VAR_INT, TapePosition::ticks,
            TapePosition::new);
}
